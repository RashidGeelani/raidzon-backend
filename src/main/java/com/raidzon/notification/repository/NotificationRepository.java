package com.raidzon.notification.repository;

import com.raidzon.notification.dto.Notification;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository @Profile("postgres")
public class NotificationRepository {
    private static final int MAX_ITEMS = 50;
    private final JdbcTemplate jdbc;
    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void notify(UUID account, Notification.Kind kind, String title, String body, UUID tournamentId, UUID teamId, UUID requestId) {
        jdbc.update("""
            INSERT INTO notifications(id, account_id, kind, title, body, tournament_id, team_id, join_request_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), account, kind.name(), clip(title, 200), body == null ? null : clip(body, 300), tournamentId, teamId, requestId);
    }
    /** Once a request is decided or withdrawn, the organizer's "new request" alert no longer needs attention. */
    public void resolveRequest(UUID requestId) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE join_request_id = ? AND kind = 'JOIN_REQUEST_RECEIVED' AND read_at IS NULL", requestId);
    }
    /** Who hears about a decision: whoever sent the request and the team's owner. */
    public List<UUID> teamDecisionRecipients(UUID requestId) {
        return jdbc.queryForList("""
            SELECT q.requested_by FROM tournament_join_requests q WHERE q.id = ?
            UNION
            SELECT t.owner_account_id FROM tournament_join_requests q JOIN teams t ON t.id = q.team_id WHERE q.id = ?
            """, UUID.class, requestId, requestId);
    }
    public Notification.Inbox inbox(UUID account) {
        var items = jdbc.query("""
            SELECT id, kind, title, body, tournament_id, team_id, created_at, read_at IS NOT NULL AS read
            FROM notifications WHERE account_id = ? ORDER BY created_at DESC, id LIMIT ?
            """, (r, i) -> new Notification(r.getObject("id", UUID.class), r.getString("kind"), r.getString("title"), r.getString("body"),
                r.getObject("tournament_id", UUID.class), r.getObject("team_id", UUID.class), r.getTimestamp("created_at").toInstant(),
                r.getBoolean("read")), account, MAX_ITEMS);
        int unread = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE account_id = ? AND read_at IS NULL", Integer.class, account);
        return new Notification.Inbox(unread, items);
    }
    public void markRead(UUID account, List<UUID> ids) {
        if (ids.isEmpty()) return;
        jdbc.update("UPDATE notifications SET read_at = now() WHERE account_id = ? AND read_at IS NULL AND id = ANY(?)",
                account, ids.toArray(UUID[]::new));
    }
    public void markAllRead(UUID account) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE account_id = ? AND read_at IS NULL", account);
    }
    private static String clip(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
}
