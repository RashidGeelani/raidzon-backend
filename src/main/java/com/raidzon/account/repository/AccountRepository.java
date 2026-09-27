package com.raidzon.account.repository;

import com.raidzon.account.dto.AccountDashboard;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository @Profile("postgres")
public class AccountRepository {
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
        int updated = jdbc.update("""
            UPDATE player_profiles SET initial_name=? WHERE claimed_by=?
            AND phone=(SELECT phone FROM user_accounts WHERE id=?)
            """, name, identity.accountId(), identity.accountId());
        if (updated != 1) throw new com.raidzon.identity.service.AuthFailure(404,"PROFILE_NOT_FOUND","No linked player profile was found.");
    }
    public AccountDashboard dashboard(UUID accountId) {
        String phone = jdbc.queryForObject("SELECT phone FROM user_accounts WHERE id=?", String.class, accountId);
        var profiles = jdbc.query("""
            SELECT p.id,p.initial_name,count(DISTINCT l.match_id) AS match_count
            FROM player_profiles p LEFT JOIN match_player_links l ON l.profile_id=p.id
            WHERE p.claimed_by=? AND p.phone=? GROUP BY p.id,p.initial_name
            """, (rs, index) -> new AccountDashboard.PlayerProfile(rs.getObject("id", UUID.class),
                rs.getString("initial_name"), rs.getLong("match_count")), accountId, phone);
        Long count = jdbc.queryForObject("SELECT count(*) FROM matches WHERE owner_account_id=?", Long.class, accountId);
        var matches = jdbc.query("""
            SELECT id, projection #>> '{teams,0,name}' AS team_a, projection #>> '{teams,1,name}' AS team_b,
                   projection->>'status' AS status, (projection #>> '{scores,0}')::integer AS score_a,
                   (projection #>> '{scores,1}')::integer AS score_b, updated_at
            FROM matches WHERE owner_account_id=? ORDER BY updated_at DESC,id LIMIT 20
            """, (rs, index) -> new AccountDashboard.MatchSummary(rs.getObject("id", UUID.class),
                rs.getString("team_a"), rs.getString("team_b"), rs.getString("status"),
                rs.getInt("score_a"), rs.getInt("score_b"), rs.getTimestamp("updated_at").getTime()), accountId);
        return new AccountDashboard(accountId,phone,profiles.isEmpty()?null:profiles.getFirst(),count==null?0:count,matches);
    }
}
