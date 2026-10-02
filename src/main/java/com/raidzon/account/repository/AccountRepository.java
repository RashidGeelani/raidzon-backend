package com.raidzon.account.repository;

import com.raidzon.account.dto.AccountDashboard;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository @Profile("postgres")
public class AccountRepository {
    private record Performance(long raidPoints, long tacklePoints, long superRaids, long superTackles) {}
    private static final java.time.Duration NAME_CHANGE_INTERVAL = java.time.Duration.ofDays(30);
    private final JdbcTemplate jdbc;
    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @org.springframework.transaction.annotation.Transactional
    public void edit(com.raidzon.identity.dto.AuthIdentity identity, String name, String verificationToken) {
        long now = System.currentTimeMillis();
        int proof = jdbc.update("""
            UPDATE auth_tokens SET revoked=true WHERE token_hash=? AND account_id=? AND device_id=?
            AND NOT revoked AND expires_at>? AND verified_at BETWEEN ? AND ?
            """, com.raidzon.identity.service.AuthService.hash(verificationToken), identity.accountId(), identity.deviceId(), now, now-300_000, now);
        if (proof != 1) throw new com.raidzon.identity.service.AuthFailure(403,"VERIFICATION_REQUIRED","Verify the same phone again before saving. Verification lasts five minutes.");
        var profiles = jdbc.queryForList("""
            SELECT id, COALESCE(display_name, initial_name) AS current_name, display_name_changed_at FROM player_profiles
            WHERE claimed_by=? AND phone=(SELECT phone FROM user_accounts WHERE id=?) FOR UPDATE
            """, identity.accountId(), identity.accountId());
        if (profiles.isEmpty()) throw new com.raidzon.identity.service.AuthFailure(404,"PROFILE_NOT_FOUND","No linked player profile was found.");
        var profile = profiles.getFirst();
        if (name.equals(profile.get("current_name"))) return;
        // The player-owned name is shown everywhere, so changes are limited and kept in history.
        var changedAt = (java.sql.Timestamp) profile.get("display_name_changed_at");
        if (changedAt != null) {
            var nextAllowed = changedAt.toInstant().plus(NAME_CHANGE_INTERVAL);
            if (nextAllowed.isAfter(java.time.Instant.ofEpochMilli(now)))
                throw new com.raidzon.identity.service.AuthFailure(429,"NAME_CHANGE_LIMIT","You can change your name once every 30 days. Next change allowed on "
                    + java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(nextAllowed.atZone(java.time.ZoneOffset.UTC)) + ".");
        }
        jdbc.update("INSERT INTO player_name_history(profile_id,old_name,new_name) VALUES (?,?,?)",
            profile.get("id"), profile.get("current_name"), name);
        jdbc.update("UPDATE player_profiles SET display_name=?, display_name_changed_at=now() WHERE id=?", name, profile.get("id"));
    }
    public AccountDashboard dashboard(UUID accountId) {
        String phone = jdbc.queryForObject("SELECT phone FROM user_accounts WHERE id=?", String.class, accountId);
        var profiles = jdbc.query("""
            SELECT p.id,COALESCE(p.display_name,p.initial_name) AS initial_name,count(DISTINCT l.match_id) AS match_count
            FROM player_profiles p LEFT JOIN match_player_links l ON l.profile_id=p.id
            WHERE p.claimed_by=? AND p.phone=? GROUP BY p.id,p.display_name,p.initial_name
            """, (rs, index) -> new AccountDashboard.PlayerProfile(rs.getObject("id", UUID.class),
                rs.getString("initial_name"), rs.getLong("match_count"), 0, 0, 0, 0), accountId, phone);
        AccountDashboard.PlayerProfile profile = null;
        if (!profiles.isEmpty()) {
            var selected = profiles.getFirst();
            var totals = performance(selected.id());
            profile = new AccountDashboard.PlayerProfile(selected.id(), selected.name(), selected.matchCount(),
                totals.raidPoints(), totals.tacklePoints(), totals.superRaids(), totals.superTackles());
        }
        Long tournamentCount = jdbc.queryForObject("SELECT count(*) FROM tournaments WHERE owner_account_id=?", Long.class, accountId);
        Long teamCount = jdbc.queryForObject("""
            SELECT count(*) FROM tournament_teams tt JOIN tournaments t ON t.id=tt.tournament_id
            WHERE t.owner_account_id=?
            """, Long.class, accountId);
        Long count = jdbc.queryForObject("SELECT count(*) FROM matches WHERE owner_account_id=?", Long.class, accountId);
        var matches = jdbc.query("""
            SELECT id, projection #>> '{teams,0,name}' AS team_a, projection #>> '{teams,1,name}' AS team_b,
                   projection->>'status' AS status, (projection #>> '{scores,0}')::integer AS score_a,
                   (projection #>> '{scores,1}')::integer AS score_b, updated_at
            FROM matches WHERE owner_account_id=? ORDER BY updated_at DESC,id LIMIT 20
            """, (rs, index) -> new AccountDashboard.MatchSummary(rs.getObject("id", UUID.class),
                rs.getString("team_a"), rs.getString("team_b"), rs.getString("status"),
                rs.getInt("score_a"), rs.getInt("score_b"), rs.getTimestamp("updated_at").getTime()), accountId);
        return new AccountDashboard(accountId,phone,profile,tournamentCount==null?0:tournamentCount,
            teamCount==null?0:teamCount,count==null?0:count,matches);
    }

    private Performance performance(UUID profileId) {
        var points = jdbc.queryForMap("""
            SELECT COALESCE(sum((player.value->>'raidPoints')::bigint),0) AS raid_points,
                   COALESCE(sum((player.value->>'tacklePoints')::bigint),0) AS tackle_points
            FROM match_player_links l JOIN matches m ON m.id=l.match_id
            CROSS JOIN LATERAL jsonb_array_elements(m.projection->'teams') AS team(value)
            CROSS JOIN LATERAL jsonb_array_elements(team.value->'players') AS player(value)
            WHERE l.profile_id=? AND player.value->>'id'=l.local_player_id::text
            """, profileId);
        var superCounts = jdbc.queryForMap("""
            WITH effective_raids AS (
                SELECT e.match_id,e.event_id,e.components,l.local_player_id
                FROM match_player_links l JOIN match_events e ON e.match_id=l.match_id
                WHERE l.profile_id=? AND e.request #>> '{intent,type}'='RAID'
                  AND NOT EXISTS (
                      SELECT 1 FROM match_events undo
                      WHERE undo.match_id=e.match_id AND undo.request #>> '{intent,type}'='UNDO'
                        AND undo.request #>> '{intent,targetEventId}'=e.event_id::text
                  )
            ), raid_totals AS (
                SELECT COALESCE(sum((component.value->>'points')::integer) FILTER (
                           WHERE component.value->>'kind' IN ('TOUCH','BONUS')
                             AND component.value->>'playerId'=r.local_player_id::text),0) AS raid_points,
                       bool_or(component.value->>'kind'='TACKLE'
                           AND component.value->>'playerId'=r.local_player_id::text) AS credited_tackle,
                       bool_or(component.value->>'kind'='SUPER_TACKLE_EXTRA') AS super_tackle
                FROM effective_raids r CROSS JOIN LATERAL jsonb_array_elements(r.components) AS component(value)
                GROUP BY r.match_id,r.event_id,r.local_player_id
            )
            SELECT count(*) FILTER (WHERE raid_points>=3) AS super_raids,
                   count(*) FILTER (WHERE credited_tackle AND super_tackle) AS super_tackles
            FROM raid_totals
            """, profileId);
        return new Performance(((Number) points.get("raid_points")).longValue(),
            ((Number) points.get("tackle_points")).longValue(),
            ((Number) superCounts.get("super_raids")).longValue(),
            ((Number) superCounts.get("super_tackles")).longValue());
    }
}
