package com.raidzon.moderation;

import java.util.List;
import java.util.UUID;

public final class Moderation {
    private Moderation() {}
    public static final List<String> REASONS = List.of("FAKE_MATCH", "WRONG_SCORE", "WRONG_PLAYERS", "OTHER");

    /** A viewer's report. details is optional free text. */
    public record ReportInput(String reason, String details) {}
    public record ReportResult(boolean alreadyReported) {}

    /** A match as the admin sees it, with its open reports. */
    public record AdminMatch(UUID id, String teamA, String teamB, int scoreA, int scoreB, String status,
                             boolean practice, String tournamentName, String ownerPhone, long createdAt,
                             Long removedAt, String removedReason, int openReports, List<String> reasons,
                             List<String> details, Long latestReportAt) {}
    public record RemoveInput(String reason) {}
}
