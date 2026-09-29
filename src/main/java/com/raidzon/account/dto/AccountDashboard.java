package com.raidzon.account.dto;

import java.util.List;
import java.util.UUID;

public record AccountDashboard(UUID accountId, String phone, PlayerProfile playerProfile,
                               long tournamentCount, long teamCount, long ownedMatchCount,
                               List<MatchSummary> recentMatches) {
    public record PlayerProfile(UUID id, String name, long matchCount, long raidPoints,
                                long tacklePoints, long superRaids, long superTackles) {}
    public record MatchSummary(UUID id, String teamA, String teamB, String status, int scoreA, int scoreB, long updatedAt) {}
}
