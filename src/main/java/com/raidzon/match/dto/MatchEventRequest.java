package com.raidzon.match.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Recorded facts only. No client totals, before/after snapshots, owner IDs or derived components. */
public record MatchEventRequest(UUID id, int baseVersion, String rulesetVersion,
                                long occurredAt, JsonNode intent) {
    public MatchEventRequest {
        if (id == null || intent == null || !intent.isObject()) throw new IllegalArgumentException("Event ID and intent are required.");
        if (occurredAt < 0 || occurredAt > 9007199254740991L) throw new IllegalArgumentException("Event time must be a safe non-negative millisecond value.");
        intent = intent.deepCopy();
    }
    @Override public JsonNode intent() { return intent.deepCopy(); }
}
