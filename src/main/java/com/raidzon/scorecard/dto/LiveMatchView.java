package com.raidzon.scorecard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * clockOffset: how far the server clock is ahead of the scoring phone's clock (ms). Match clocks in
 * the state use the phone's time, so viewers subtract this before counting down.
 * tournamentId: the tournament this match is a fixture of, or null.
 */
public record LiveMatchView(UUID matchId, JsonNode state, List<Event> events, int version,
                            long serverTime, long lastSyncedAt, long clockOffset, UUID tournamentId) {
    /** touchedIds / selfOutIds: defenders put out in a raid (player IDs), so viewers can name them. */
    public record Event(UUID id, String type, String summary, int raidNumber, JsonNode components, JsonNode touchedIds, JsonNode selfOutIds) {}
}
