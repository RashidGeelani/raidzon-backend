package com.raidzon.tournament.repository;

import com.raidzon.identity.service.AuthFailure;
import com.raidzon.tournament.dto.JoinRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository @Profile("postgres")
public class JoinRequestRepository {
    private final JdbcTemplate jdbc;
    public JoinRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Stored(UUID id, UUID tournamentId, UUID teamId, String status, UUID tournamentTeamId) {}
    public record TournamentInfo(UUID owner, boolean registrationOpen) {}

    public TournamentInfo tournament(UUID tournamentId, boolean lock) {
        var rows = jdbc.query("SELECT owner_account_id, registration_open FROM tournaments WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                (r, i) -> new TournamentInfo(r.getObject(1, UUID.class), r.getBoolean(2)), tournamentId);
        if (rows.isEmpty()) throw new AuthFailure(404, "TOURNAMENT_NOT_FOUND", "Tournament not found.");
        return rows.getFirst();
    }
    public void setRegistrationOpen(UUID tournamentId, boolean open) {
        jdbc.update("UPDATE tournaments SET registration_open = ? WHERE id = ?", open, tournamentId);
    }
    public boolean registered(UUID tournamentId, UUID teamId) {
        return jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id = ? AND team_id = ?", Integer.class, tournamentId, teamId) > 0;
    }
    public boolean nameTaken(UUID tournamentId, String name) {
        return jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id = ? AND lower(name) = lower(?)", Integer.class, tournamentId, name) > 0;
    }
    public boolean pending(UUID tournamentId, UUID teamId) {
        return jdbc.queryForObject("SELECT count(*) FROM tournament_join_requests WHERE tournament_id = ? AND team_id = ? AND status = 'PENDING'",
                Integer.class, tournamentId, teamId) > 0;
    }
    /** Inserts once; a retry with the same ID and facts is a no-op, different facts are rejected. */
    public void insert(UUID id, UUID tournamentId, UUID teamId, UUID requestedBy, String message) {
        jdbc.update("""
            INSERT INTO tournament_join_requests(id, tournament_id, team_id, requested_by, message) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """, id, tournamentId, teamId, requestedBy, message);
        var row = jdbc.queryForMap("SELECT tournament_id, team_id, message FROM tournament_join_requests WHERE id = ?", id);
        if (!tournamentId.equals(row.get("tournament_id")) || !teamId.equals(row.get("team_id")) || !Objects.equals(message, row.get("message")))
            throw new AuthFailure(409, "ID_REUSED", "Request ID was reused with different details.");
    }
    public boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM tournament_join_requests WHERE id = ?", Integer.class, id) > 0;
    }
    public Stored lock(UUID id) {
        var rows = jdbc.query("SELECT id, tournament_id, team_id, status, tournament_team_id FROM tournament_join_requests WHERE id = ? FOR UPDATE",
                (r, i) -> new Stored(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class), r.getString(4), r.getObject(5, UUID.class)), id);
        if (rows.isEmpty()) throw new AuthFailure(404, "REQUEST_NOT_FOUND", "Join request not found.");
        return rows.getFirst();
    }
    public void decide(UUID id, String status, UUID by, String note, UUID tournamentTeamId) {
        jdbc.update("""
            UPDATE tournament_join_requests SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?, tournament_team_id = ?
            WHERE id = ? AND status = 'PENDING'
            """, status, by, note, tournamentTeamId, id);
    }

    private static final String SELECT = """
        SELECT q.id, q.tournament_id, t.name AS tournament_name, q.team_id, tm.name AS team_name, tm.city AS team_city,
               (SELECT count(*) FROM team_members m WHERE m.team_id = q.team_id AND m.left_at IS NULL) AS squad_size,
               ARRAY(SELECT COALESCE(p.display_name, m.squad_name) FROM team_members m JOIN player_profiles p ON p.id = m.profile_id
                     WHERE m.team_id = q.team_id AND m.left_at IS NULL ORDER BY m.joined_at, m.id) AS players,
               (SELECT COALESCE(p.display_name, p.initial_name) FROM user_accounts u JOIN player_profiles p ON p.phone = u.phone
                WHERE u.id = q.requested_by) AS requested_by_name,
               q.message, q.status, q.decision_note, q.created_at, q.decided_at, q.tournament_team_id
        FROM tournament_join_requests q JOIN tournaments t ON t.id = q.tournament_id JOIN teams tm ON tm.id = q.team_id
        """;
    private static JoinRequest row(ResultSet r, int i) throws SQLException {
        var players = r.getArray("players");
        return new JoinRequest(r.getObject("id", UUID.class), r.getObject("tournament_id", UUID.class), r.getString("tournament_name"),
                r.getObject("team_id", UUID.class), r.getString("team_name"), r.getString("team_city"), r.getInt("squad_size"),
                players == null ? List.of() : Arrays.asList((String[]) players.getArray()), r.getString("requested_by_name"),
                r.getString("message"), r.getString("status"), r.getString("decision_note"),
                r.getTimestamp("created_at").toInstant(), r.getTimestamp("decided_at") == null ? null : r.getTimestamp("decided_at").toInstant(),
                r.getObject("tournament_team_id", UUID.class));
    }
    public JoinRequest one(UUID id) { return jdbc.query(SELECT + " WHERE q.id = ?", JoinRequestRepository::row, id).getFirst(); }
    public List<JoinRequest> forTournament(UUID tournamentId) {
        return jdbc.query(SELECT + " WHERE q.tournament_id = ? ORDER BY q.status <> 'PENDING', q.created_at DESC LIMIT 200", JoinRequestRepository::row, tournamentId);
    }
    public List<JoinRequest> forTeam(UUID teamId) {
        return jdbc.query(SELECT + " WHERE q.team_id = ? ORDER BY q.created_at DESC LIMIT 100", JoinRequestRepository::row, teamId);
    }
}
