package com.raidzon.team.repository;

import com.raidzon.identity.service.AuthFailure;
import com.raidzon.team.dto.Team;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** JDBC access for reusable teams. Callers run inside a service transaction and lock the team first. */
@Repository @Profile("postgres")
public class TeamRepository {
    private final JdbcTemplate jdbc;
    public TeamRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** The caller's strongest role on the team, or null when the team is missing or not theirs. */
    public Team.Role role(UUID teamId, UUID account) {
        var rows = jdbc.queryForList("""
            SELECT CASE WHEN t.owner_account_id = ? THEN 'OWNER' ELSE (
                SELECT s.role FROM team_staff s
                JOIN player_profiles p ON p.id = s.profile_id
                JOIN user_accounts u ON u.phone = p.phone
                WHERE s.team_id = t.id AND u.id = ?
                ORDER BY CASE s.role WHEN 'MANAGER' THEN 0 ELSE 1 END LIMIT 1) END
            FROM teams t WHERE t.id = ?
            """, String.class, account, account, teamId);
        return rows.isEmpty() || rows.getFirst() == null ? null : Team.Role.valueOf(rows.getFirst());
    }

    public void lock(UUID teamId) {
        if (jdbc.queryForList("SELECT id FROM teams WHERE id = ? FOR UPDATE", UUID.class, teamId).isEmpty())
            throw new AuthFailure(404, "TEAM_NOT_FOUND", "Team not found.");
    }

    public void create(UUID id, UUID owner, String name, String city) {
        jdbc.update("INSERT INTO teams(id, owner_account_id, name, city) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                id, owner, name, city);
        var rows = jdbc.queryForList("SELECT owner_account_id, name, city FROM teams WHERE id = ? FOR UPDATE", id);
        var row = rows.getFirst();
        if (!owner.equals(row.get("owner_account_id")) || !name.equals(row.get("name")) || !Objects.equals(city, row.get("city")))
            throw new AuthFailure(409, "ID_REUSED", "Team ID was reused with different details.");
    }

    public void updateDetails(UUID id, String name, String city) {
        jdbc.update("UPDATE teams SET name = ?, city = ? WHERE id = ?", name, city, id);
    }

    public void archive(UUID id) { jdbc.update("UPDATE teams SET archived = true WHERE id = ?", id); }

    public boolean archived(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT archived FROM teams WHERE id = ?", Boolean.class, id));
    }

    public void touch(UUID id) {
        jdbc.update("UPDATE teams SET revision = revision + 1, updated_at = now() WHERE id = ?", id);
    }

    /** Reuses the global identity for a phone, creating an unclaimed profile when none exists. */
    public UUID profileFor(String phone, String name) {
        jdbc.update("""
            INSERT INTO player_profiles(id, phone, initial_name, claimed_by)
            VALUES (?, ?, ?, (SELECT id FROM user_accounts WHERE phone = ?)) ON CONFLICT (phone) DO NOTHING
            """, UUID.randomUUID(), phone, name, phone);
        return jdbc.queryForObject("SELECT id FROM player_profiles WHERE phone = ?", UUID.class, phone);
    }

    public record StoredMember(UUID id, UUID teamId, UUID profileId, boolean active, Integer jersey) {}
    public StoredMember member(UUID memberId) {
        var rows = jdbc.query("SELECT id, team_id, profile_id, left_at IS NULL AS active, jersey FROM team_members WHERE id = ?",
                (r, i) -> new StoredMember(r.getObject("id", UUID.class), r.getObject("team_id", UUID.class),
                        r.getObject("profile_id", UUID.class), r.getBoolean("active"), r.getObject("jersey", Integer.class)), memberId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int activeMembers(UUID teamId) {
        return jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ? AND left_at IS NULL", Integer.class, teamId);
    }

    public boolean activeProfile(UUID teamId, UUID profileId) {
        return jdbc.queryForObject("SELECT count(*) FROM team_members WHERE team_id = ? AND profile_id = ? AND left_at IS NULL",
                Integer.class, teamId, profileId) > 0;
    }

    public boolean jerseyTaken(UUID teamId, int jersey, UUID exceptMember) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM team_members WHERE team_id = ? AND jersey = ? AND left_at IS NULL AND id <> ?
            """, Integer.class, teamId, jersey, exceptMember) > 0;
    }

    public void addMember(UUID id, UUID teamId, UUID profileId, String name, Integer jersey, String playingRole) {
        jdbc.update("INSERT INTO team_members(id, team_id, profile_id, squad_name, jersey, playing_role) VALUES (?, ?, ?, ?, ?, ?)",
                id, teamId, profileId, name, jersey, playingRole);
    }

    public void editMember(UUID id, String name, Integer jersey, String playingRole) {
        jdbc.update("UPDATE team_members SET squad_name = ?, jersey = ?, playing_role = ? WHERE id = ?", name, jersey, playingRole, id);
    }

    public void removeMember(UUID id) {
        jdbc.update("UPDATE team_members SET left_at = now(), leadership = NULL WHERE id = ? AND left_at IS NULL", id);
    }

    public void setLeadership(UUID teamId, UUID captain, UUID viceCaptain) {
        jdbc.update("UPDATE team_members SET leadership = NULL WHERE team_id = ? AND leadership IS NOT NULL", teamId);
        if (captain != null) jdbc.update("UPDATE team_members SET leadership = 'CAPTAIN' WHERE id = ?", captain);
        if (viceCaptain != null) jdbc.update("UPDATE team_members SET leadership = 'VICE_CAPTAIN' WHERE id = ?", viceCaptain);
    }

    public int staffCount(UUID teamId) {
        return jdbc.queryForObject("SELECT count(*) FROM team_staff WHERE team_id = ?", Integer.class, teamId);
    }

    public boolean hasStaff(UUID teamId, UUID profileId, String role) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM team_staff WHERE team_id = ? AND profile_id = ? AND role = ?)", Boolean.class, teamId, profileId, role));
    }

    public boolean addStaff(UUID teamId, UUID profileId, String role, String name) {
        return jdbc.update("INSERT INTO team_staff(team_id, profile_id, role, name) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                teamId, profileId, role, name) == 1;
    }

    public boolean removeStaff(UUID teamId, UUID profileId, String role) {
        return jdbc.update("DELETE FROM team_staff WHERE team_id = ? AND profile_id = ? AND role = ?", teamId, profileId, role) == 1;
    }

    /** The saved team's active squad in squad order, as roster entries (organizer's squad names and phones). */
    public record Squad(String name, boolean archived, List<com.raidzon.tournament.dto.Tournament.RosterPlayer> players) {}
    public Squad squad(UUID teamId) {
        var team = jdbc.queryForMap("SELECT name, archived FROM teams WHERE id = ?", teamId);
        var players = jdbc.query("""
            SELECT m.squad_name, p.phone FROM team_members m JOIN player_profiles p ON p.id = m.profile_id
            WHERE m.team_id = ? AND m.left_at IS NULL
            ORDER BY CASE m.leadership WHEN 'CAPTAIN' THEN 0 WHEN 'VICE_CAPTAIN' THEN 1 ELSE 2 END, m.joined_at, m.id
            """, (r, i) -> new com.raidzon.tournament.dto.Tournament.RosterPlayer(r.getString("squad_name"), r.getString("phone")), teamId);
        return new Squad((String) team.get("name"), (Boolean) team.get("archived"), players);
    }

    public List<Team.Summary> mine(UUID account) {
        return jdbc.query("""
            WITH mine AS (
                SELECT t.id, 'OWNER' AS my_role FROM teams t WHERE t.owner_account_id = ?
                UNION ALL
                SELECT s.team_id, s.role FROM team_staff s
                JOIN player_profiles p ON p.id = s.profile_id
                JOIN user_accounts u ON u.phone = p.phone WHERE u.id = ?
            ), ranked AS (
                SELECT DISTINCT ON (id) id, my_role FROM mine
                ORDER BY id, CASE my_role WHEN 'OWNER' THEN 0 WHEN 'MANAGER' THEN 1 ELSE 2 END
            )
            SELECT t.id, t.name, t.city, t.archived, r.my_role,
                (SELECT count(*) FROM team_members m WHERE m.team_id = t.id AND m.left_at IS NULL) AS squad_size,
                (SELECT COALESCE(p.display_name, m.squad_name) FROM team_members m JOIN player_profiles p ON p.id = m.profile_id
                 WHERE m.team_id = t.id AND m.leadership = 'CAPTAIN' AND m.left_at IS NULL) AS captain_name
            FROM ranked r JOIN teams t ON t.id = r.id
            ORDER BY t.archived, t.updated_at DESC, t.id
            LIMIT 200
            """, (r, i) -> new Team.Summary(r.getObject("id", UUID.class), r.getString("name"), r.getString("city"),
                r.getBoolean("archived"), r.getString("my_role"), r.getInt("squad_size"), r.getString("captain_name")),
                account, account);
    }

    public Team.Detail detail(UUID teamId, Team.Role myRole) {
        var team = jdbc.queryForMap("""
            SELECT t.id, t.name, t.city, t.archived, t.revision, COALESCE(p.display_name, p.initial_name) AS owner_name
            FROM teams t JOIN user_accounts u ON u.id = t.owner_account_id
            LEFT JOIN player_profiles p ON p.phone = u.phone WHERE t.id = ?
            """, teamId);
        var members = jdbc.query("""
            SELECT m.id, m.profile_id, COALESCE(p.display_name, m.squad_name) AS name, m.squad_name, p.phone,
                   m.jersey, m.playing_role, m.leadership, p.claimed_by IS NOT NULL AS claimed
            FROM team_members m JOIN player_profiles p ON p.id = m.profile_id
            WHERE m.team_id = ? AND m.left_at IS NULL
            ORDER BY CASE m.leadership WHEN 'CAPTAIN' THEN 0 WHEN 'VICE_CAPTAIN' THEN 1 ELSE 2 END, m.joined_at, m.id
            """, (r, i) -> new Team.Member(r.getObject("id", UUID.class), r.getObject("profile_id", UUID.class),
                r.getString("name"), r.getString("squad_name"), r.getString("phone"), r.getObject("jersey", Integer.class),
                r.getString("playing_role"), r.getString("leadership"), r.getBoolean("claimed")), teamId);
        var staff = jdbc.query("""
            SELECT s.profile_id, COALESCE(p.display_name, s.name) AS name, p.phone, s.role
            FROM team_staff s JOIN player_profiles p ON p.id = s.profile_id
            WHERE s.team_id = ? ORDER BY CASE s.role WHEN 'MANAGER' THEN 0 ELSE 1 END, s.added_at, s.profile_id
            """, (r, i) -> new Team.Staff(r.getObject("profile_id", UUID.class), r.getString("name"), r.getString("phone"),
                r.getString("role")), teamId);
        return new Team.Detail((UUID) team.get("id"), (String) team.get("name"), (String) team.get("city"),
                (Boolean) team.get("archived"), ((Number) team.get("revision")).intValue(), myRole.name(),
                (String) team.get("owner_name"), members, staff);
    }
}
