package com.raidzon.match.repository;

import com.raidzon.match.domain.MatchHistory;
import com.raidzon.match.domain.MatchState;
import com.raidzon.match.dto.MatchEventRequest;
import com.raidzon.match.dto.MatchJsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;

/** JDBC operations participate in the service transaction. Lock the match before reading its log. */
public final class MatchRepository {
    public record StoredMatch(UUID id, UUID owner, UUID scorer, UUID session, String ruleset, String creationFingerprint,
                              MatchState initial, MatchState state, int version, boolean deleted) {}
    public record StoredEvent(MatchEventRequest request, String fingerprint, int sequence) {}
    public record EventRef(UUID eventId, String fingerprint, int sequence, long occurredAt) {}
    private final JdbcTemplate jdbc;
    private final MatchJsonCodec codec;
    public MatchRepository(JdbcTemplate jdbc, MatchJsonCodec codec) { this.jdbc = jdbc; this.codec = codec; }
    /** For checks that must run in the same transaction as a match write (e.g. a fresh OTP proof). */
    public JdbcTemplate jdbc() { return jdbc; }

    public boolean insert(UUID id, UUID owner, UUID session, String ruleset, String fingerprint, MatchState state) {
        return insert(id, owner, session, ruleset, fingerprint, state, false);
    }
    public boolean insert(UUID id, UUID owner, UUID session, String ruleset, String fingerprint, MatchState state, boolean practice) {
        String snapshot = codec.write(state);
        return jdbc.update("""
                INSERT INTO matches(id, owner_account_id, scoring_account_id, scoring_session_id, ruleset_version, creation_fingerprint, initial_state, projection, practice)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) ON CONFLICT (id) DO NOTHING
                """, id, owner, owner, session, ruleset, fingerprint, snapshot, snapshot, practice) == 1;
    }
    public StoredMatch lock(UUID id) {
        var rows = jdbc.query("SELECT * FROM matches WHERE id = ? FOR UPDATE", (rs, row) -> new StoredMatch(
                rs.getObject("id", UUID.class), rs.getObject("owner_account_id", UUID.class), rs.getObject("scoring_account_id", UUID.class),
                rs.getObject("scoring_session_id", UUID.class), rs.getString("ruleset_version"), rs.getString("creation_fingerprint"),
                codec.read(rs.getString("initial_state"), MatchState.class), codec.read(rs.getString("projection"), MatchState.class), rs.getInt("version"),
                rs.getTimestamp("deleted_at") != null), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Match not found.");
        return rows.getFirst();
    }
    public List<StoredEvent> events(UUID id) {
        return jdbc.query("SELECT request, request_fingerprint, sequence FROM match_events WHERE match_id = ? ORDER BY sequence",
                (rs, row) -> new StoredEvent(codec.read(rs.getString("request"), MatchEventRequest.class), rs.getString("request_fingerprint"), rs.getInt("sequence")), id);
    }
    /** The event with this ID (a retry) and the latest event, in one round trip. */
    public List<EventRef> eventOrLatest(UUID matchId, UUID eventId, int latestSequence) {
        return jdbc.query("""
                SELECT event_id, request_fingerprint, sequence, (request->>'occurredAt')::bigint AS occurred_at
                FROM match_events WHERE match_id = ? AND (event_id = ? OR sequence = ?)
                """, (rs, row) -> new EventRef(rs.getObject("event_id", UUID.class), rs.getString("request_fingerprint"),
                        rs.getInt("sequence"), rs.getLong("occurred_at")), matchId, eventId, latestSequence);
    }
    public void append(UUID matchId, MatchEventRequest request, String fingerprint, MatchHistory.Event event) {
        jdbc.update("""
                INSERT INTO match_events(match_id, event_id, sequence, base_version, request_fingerprint, request,
                                         before_state, after_state, components, summary)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """, matchId, request.id(), event.sequence(), request.baseVersion(), fingerprint, codec.write(request),
                codec.write(event.before()), codec.write(event.result().state()), codec.write(event.result().components()), event.result().summary());
        int updated = jdbc.update("UPDATE matches SET projection = ?::jsonb, version = ?, updated_at = now() WHERE id = ? AND version = ?",
                codec.write(event.result().state()), event.sequence(), matchId, request.baseVersion());
        if (updated != 1) throw new IllegalStateException("Projection version changed during event commit.");
    }
    /** What a phone taking over a match needs besides its history. fixtureId is null for a match outside a tournament. */
    public record TakeoverFacts(boolean removed, boolean practice, long createdAt, UUID tournamentId, UUID fixtureId, boolean knockout) {}
    public TakeoverFacts takeoverFacts(UUID matchId) {
        return jdbc.queryForObject("""
                SELECT m.removed_at IS NOT NULL AS removed, m.practice, m.created_at, f.tournament_id, f.id AS fixture_id,
                       COALESCE(f.stage IN ('KNOCKOUT','THIRD_PLACE'), false) AS knockout
                FROM matches m LEFT JOIN tournament_fixtures f ON f.match_id = m.id WHERE m.id = ?
                """, (rs, row) -> new TakeoverFacts(rs.getBoolean("removed"), rs.getBoolean("practice"), rs.getTimestamp("created_at").getTime(),
                        rs.getObject("tournament_id", UUID.class), rs.getObject("fixture_id", UUID.class), rs.getBoolean("knockout")), matchId);
    }
    /** The fence: from now on only this device can write the match. Call with the match locked. */
    public void moveScoring(UUID matchId, UUID session) {
        jdbc.update("UPDATE matches SET scoring_session_id = ? WHERE id = ?", session, matchId);
    }
    /** Audit row; from_session is the device scoring the match when the attempt was made. Nothing is written for an unknown match. */
    public void recordTakeover(UUID matchId, UUID account, UUID toSession, String result) {
        jdbc.update("""
                INSERT INTO match_takeovers(match_id, account_id, from_session, to_session, result)
                SELECT id, ?, scoring_session_id, ?, ? FROM matches WHERE id = ?
                """, account, toSession, result, matchId);
    }
    public int takeoversInLastHour(UUID matchId, UUID account) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM match_takeovers WHERE match_id = ? AND account_id = ? AND created_at > now() - interval '1 hour'
                """, Integer.class, matchId, account);
        return count == null ? 0 : count;
    }
    /** True when the match is linked to a knockout or third-place fixture, which cannot end in a draw. */
    public boolean linkedToKnockout(UUID matchId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tournament_fixtures WHERE match_id=? AND stage IN ('KNOCKOUT','THIRD_PLACE'))", Boolean.class, matchId));
    }
}
