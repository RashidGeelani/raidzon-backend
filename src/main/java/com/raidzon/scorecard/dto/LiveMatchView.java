package com.raidzon.scorecard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record LiveMatchView(UUID matchId, JsonNode state, List<Event> events, int version,
                            long serverTime, long lastSyncedAt) {
    public record Event(UUID id, String type, String summary, int raidNumber, JsonNode components) {}
}
