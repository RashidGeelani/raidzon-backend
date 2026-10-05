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
    /** True when the match is linked to a knockout or third-place fixture, which cannot end in a draw. */
    public boolean linkedToKnockout(UUID matchId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tournament_fixtures WHERE match_id=? AND stage IN ('KNOCKOUT','THIRD_PLACE'))", Boolean.class, matchId));
    }
}
