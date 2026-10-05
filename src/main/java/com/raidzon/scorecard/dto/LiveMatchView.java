package com.raidzon.scorecard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * clockOffset: how far the server clock is ahead of the scoring phone's clock (ms). Match clocks in
 * the state use the phone's time, so viewers subtract this before counting down.
 */
public record LiveMatchView(UUID matchId, JsonNode state, List<Event> events, int version,
                            long serverTime, long lastSyncedAt, long clockOffset) {
    public record Event(UUID id, String type, String summary, int raidNumber, JsonNode components) {}
}
