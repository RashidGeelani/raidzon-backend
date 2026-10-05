package com.raidzon.moderation;

import com.raidzon.identity.service.AuthFailure;
import com.raidzon.notification.dto.Notification;
import com.raidzon.notification.repository.NotificationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Match reports from viewers, and the admin actions that resolve them. */
@Repository @Profile("postgres")
public class ModerationRepository {
    static final int REPORTS_PER_DAY = 20;
    private final JdbcTemplate jdbc;
    private final NotificationRepository notifications;
    public ModerationRepository(JdbcTemplate jdbc, NotificationRepository notifications) { this.jdbc = jdbc; this.notifications = notifications; }

    /** Records a report on a match anyone can watch. One report per account per match. */
    @Transactional
    public Moderation.ReportResult report(UUID matchId, UUID reporter, String reason, String details) {
        boolean watchable = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM matches m WHERE m.id=? AND m.removed_at IS NULL AND (
                EXISTS(SELECT 1 FROM tournament_fixtures f WHERE f.match_id=m.id)
                OR EXISTS(SELECT 1 FROM public_scorecards s WHERE s.match_id=m.id AND s.published)))
            """, Boolean.class, matchId));
        if (!watchable) throw new AuthFailure(404, "MATCH_NOT_PUBLIC", "This match is not available to report.");
        // Serialize one reporter's requests so the daily limit can't be raced.
        jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", reporter);
        Integer today = jdbc.queryForObject(
            "SELECT count(*) FROM match_reports WHERE reporter_account_id=? AND created_at>now()-interval '1 day'", Integer.class, reporter);
        if (today != null && today >= REPORTS_PER_DAY)
            throw new AuthFailure(429, "REPORT_LIMIT", "You've sent a lot of reports today. Try again tomorrow.");
        int inserted = jdbc.update("""
            INSERT INTO match_reports(id,match_id,reporter_account_id,reason,details) VALUES (?,?,?,?,?)
            ON CONFLICT (match_id,reporter_account_id) DO NOTHING
            """, UUID.randomUUID(), matchId, reporter, reason, details);
        return new Moderation.ReportResult(inserted == 0);
    }

    /** Matches with open reports (most reported first), or the one match asked for by ID. */
    public List<Moderation.AdminMatch> matches(UUID onlyMatch) {
        return jdbc.query("""
            SELECT m.id, m.projection #>> '{teams,0,name}' AS team_a, m.projection #>> '{teams,1,name}' AS team_b,
                (m.projection #>> '{scores,0}')::integer AS score_a, (m.projection #>> '{scores,1}')::integer AS score_b,
                m.projection->>'status' AS status, m.practice, t.name AS tournament_name, o.phone AS owner_phone,
                m.created_at, m.removed_at, m.removed_reason, count(r.id) AS open_reports,
                COALESCE(array_agg(DISTINCT r.reason) FILTER (WHERE r.id IS NOT NULL), '{}') AS reasons,
                COALESCE((array_agg(r.details ORDER BY r.created_at DESC) FILTER (WHERE r.details IS NOT NULL))[1:5], '{}') AS details,
                max(r.created_at) AS latest
            FROM matches m JOIN user_accounts o ON o.id=m.owner_account_id
            LEFT JOIN match_reports r ON r.match_id=m.id AND r.status='OPEN'
            LEFT JOIN tournament_fixtures f ON f.match_id=m.id LEFT JOIN tournaments t ON t.id=f.tournament_id
            WHERE (?::uuid IS NULL AND EXISTS(SELECT 1 FROM match_reports x WHERE x.match_id=m.id AND x.status='OPEN')) OR m.id=?
            GROUP BY m.id, o.phone, t.name
            ORDER BY count(r.id) DESC, max(r.created_at) DESC NULLS LAST, m.id LIMIT 100
            """, (r, i) -> new Moderation.AdminMatch(r.getObject("id", UUID.class), r.getString("team_a"), r.getString("team_b"),
                r.getInt("score_a"), r.getInt("score_b"), r.getString("status"), r.getBoolean("practice"), r.getString("tournament_name"),
                r.getString("owner_phone"), r.getTimestamp("created_at").getTime(), millis(r.getTimestamp("removed_at")),
                r.getString("removed_reason"), r.getInt("open_reports"), strings(r.getArray("reasons")),
                strings(r.getArray("details")), millis(r.getTimestamp("latest"))), onlyMatch, onlyMatch);
    }

    /**
     * Takes a match down: hidden from watchers and shared scorecards, left out of every stat, and
     * unlinked from its tournament fixture so the table no longer counts it. Open reports are closed.
     */
    @Transactional
    public void remove(UUID matchId, UUID admin, String reason) {
        var match = lockMatch(matchId);
        if (match.get("removed_at") != null) return;
        UUID tournament = takeDown(matchId, admin, reason, false);
        notifications.notify((UUID) match.get("owner_account_id"), Notification.Kind.MATCH_REMOVED,
            "Your match " + match.get("team_a") + " vs " + match.get("team_b") + " was removed",
            "RaidzOn removed it from public pages and stats. Reason: " + reason, tournament, null, null);
    }

    /**
     * The organizer deletes a match that hasn't started or is paused. Same effect as a removal, and the match also
     * leaves the organizer's own list and can't be uploaded or scored again.
     */
    @Transactional
    public void deleteByOwner(UUID matchId, UUID owner) {
        var match = lockMatch(matchId);
        if (!owner.equals(match.get("owner_account_id")))
            throw new AuthFailure(403, "NOT_ORGANIZER", "Only the match organizer can delete this match.");
        if (match.get("deleted_at") != null) return;
        // Same rule as the app: before anything is recorded, or while the match is paused.
        if (((Number) match.get("version")).intValue() != 0 && !"PAUSED".equals(match.get("status")))
            throw new AuthFailure(409, "MATCH_NOT_PAUSED", "Pause the match before deleting it. A finished match can't be deleted.");
        takeDown(matchId, owner, "Deleted by the organizer", true);
    }

    private java.util.Map<String, Object> lockMatch(UUID matchId) {
        var rows = jdbc.queryForList("""
            SELECT owner_account_id, removed_at, deleted_at, version, projection->>'status' AS status,
                projection #>> '{teams,0,name}' AS team_a, projection #>> '{teams,1,name}' AS team_b
            FROM matches WHERE id=? FOR UPDATE
            """, matchId);
        if (rows.isEmpty()) throw new AuthFailure(404, "MATCH_NOT_FOUND", "Match not found.");
        return rows.getFirst();
    }

    /** Hides the match everywhere and frees its fixture. Returns the tournament it was linked to, if any. */
    private UUID takeDown(UUID matchId, UUID actor, String reason, boolean deleted) {
        var fixtures = jdbc.queryForList("SELECT id, tournament_id FROM tournament_fixtures WHERE match_id=? FOR UPDATE", matchId);
        UUID tournament = null;
        if (!fixtures.isEmpty()) {
            UUID fixture = (UUID) fixtures.getFirst().get("id");
            tournament = (UUID) fixtures.getFirst().get("tournament_id");
            String won = "W:" + fixture, lost = "L:" + fixture;
            Integer played = jdbc.queryForObject("""
                SELECT count(*) FROM tournament_fixtures WHERE match_id IS NOT NULL AND (source_a IN (?,?) OR source_b IN (?,?))
                """, Integer.class, won, lost, won, lost);
            if (played != null && played > 0)
                throw new AuthFailure(409, "LATER_ROUND_PLAYED", "A later knockout match was already played with this result. Remove that match first.");
            // The next round's teams were filled from this result; clear them so they're decided again.
            jdbc.update("UPDATE tournament_fixtures SET team_a_id=NULL WHERE match_id IS NULL AND source_a IN (?,?)", won, lost);
            jdbc.update("UPDATE tournament_fixtures SET team_b_id=NULL WHERE match_id IS NULL AND source_b IN (?,?)", won, lost);
            jdbc.update("UPDATE tournament_fixtures SET match_id=NULL WHERE id=?", fixture);
        }
        jdbc.update("""
            UPDATE matches SET removed_at=COALESCE(removed_at,now()), removed_by=COALESCE(removed_by,?),
                removed_reason=COALESCE(removed_reason,?), deleted_at=CASE WHEN ? THEN now() ELSE deleted_at END WHERE id=?
            """, actor, reason, deleted, matchId);
        jdbc.update("UPDATE public_scorecards SET published=false WHERE match_id=?", matchId);
        jdbc.update("UPDATE match_reports SET status='ACTIONED', resolved_at=now(), resolved_by=? WHERE match_id=? AND status='OPEN'", actor, matchId);
        return tournament;
    }

    /** Undoes a removal or an organizer's delete. The match is visible and counted again, but stays unlinked from its fixture. */
    @Transactional
    public void restore(UUID matchId) {
        if (jdbc.update("UPDATE matches SET removed_at=NULL, removed_by=NULL, removed_reason=NULL, deleted_at=NULL WHERE id=?", matchId) != 1)
            throw new AuthFailure(404, "MATCH_NOT_FOUND", "Match not found.");
    }

    /** Closes a match's open reports without action. */
    public void dismiss(UUID matchId, UUID admin) {
        jdbc.update("UPDATE match_reports SET status='DISMISSED', resolved_at=now(), resolved_by=? WHERE match_id=? AND status='OPEN'", admin, matchId);
    }

    private static Long millis(Timestamp value) { return value == null ? null : value.getTime(); }
    private static List<String> strings(java.sql.Array value) throws java.sql.SQLException {
        if (value == null) return List.of();
        return Arrays.stream((Object[]) value.getArray()).map(String::valueOf).toList();
    }
}
