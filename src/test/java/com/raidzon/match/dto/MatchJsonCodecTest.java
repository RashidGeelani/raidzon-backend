package com.raidzon.match.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.match.domain.MatchEngine;
import com.raidzon.match.dto.MatchEventRequest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MatchJsonCodecTest {
    @Test void creationFingerprintRetainsTheLegacyShape() {
        var json = new ObjectMapper(); var codec = new MatchJsonCodec(json);
        var request = new CreateMatchRequest(UUID.randomUUID(), java.util.List.of(), 0, 20, 30, 1000, "raidzon-v2");
        var oldShape = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(request);
        oldShape.remove("rulesetVersion");
        oldShape.remove("practice");
        assertEquals(codec.fingerprint(oldShape), codec.fingerprint(request));
        // An explicit practice:false is a normal match, so it keeps the same hash.
        var notPractice = new CreateMatchRequest(request.matchId(), request.teams(), 0, 20, 30, 1000, "raidzon-v2", false);
        assertEquals(codec.fingerprint(request), codec.fingerprint(notPractice));
    }
    @Test void practiceMatchesHashDifferently() {
        var codec = new MatchJsonCodec(new ObjectMapper());
        var normal = new CreateMatchRequest(UUID.randomUUID(), java.util.List.of(), 0, 20, 30, 1000, "raidzon-v5");
        var practice = new CreateMatchRequest(normal.matchId(), normal.teams(), 0, 20, 30, 1000, "raidzon-v5", true);
        assertFalse(codec.fingerprint(normal).equals(codec.fingerprint(practice)));
    }
    @Test void everySharedActionSurvivesStorageRoundTrip() throws Exception {
        var json = new ObjectMapper(); var codec = new MatchJsonCodec(json);
        try (var stream = getClass().getResourceAsStream("/matches/match-scenarios.json")) {
            assertNotNull(stream);
            for (var scenario : json.readTree(stream)) for (var step : scenario.path("events")) {
                var request = new MatchEventRequest(UUID.randomUUID(), 0, MatchEngine.RULESET_VERSION, step.path("at").asLong(), step.path("intent"));
                var restored = codec.read(codec.write(request), MatchEventRequest.class);
                assertEquals(codec.input(request), codec.input(restored), scenario.path("name").asText());
                assertEquals(codec.fingerprint(request), codec.fingerprint(restored));
            }
        }
    }
}
