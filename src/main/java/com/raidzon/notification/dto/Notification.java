package com.raidzon.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An in-app alert. tournamentId/teamId tell the app which screen to open. */
public record Notification(UUID id, String kind, String title, String body, UUID tournamentId, UUID teamId,
                           Instant createdAt, boolean read) {
    public enum Kind { JOIN_REQUEST_RECEIVED, JOIN_REQUEST_WITHDRAWN, JOIN_REQUEST_APPROVED, JOIN_REQUEST_REJECTED }
    public record Inbox(int unread, List<Notification> items) {}
    public record ReadInput(List<UUID> ids, boolean all) {}
}

