package com.raidzon.scoring.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RaidScoringEngineTest {
    @TestFactory
    List<DynamicTest> sharedScenarios() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scenarios;
        try (InputStream input = getClass().getResourceAsStream("/scoring/raid-scenarios.json")) {
            assertNotNull(input, "Shared fixtures must be on the test classpath");
            scenarios = mapper.readTree(input);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode scenario : scenarios) {
            tests.add(DynamicTest.dynamicTest(scenario.get("name").asText(), () -> {
                RaidFacts facts = mapper.treeToValue(scenario.get("facts"), RaidFacts.class);
                RaidScoringEngine engine = new RaidScoringEngine();
                if (scenario.has("error")) {
                    var failure = assertThrows(IllegalArgumentException.class, () -> engine.evaluate(facts));
                    assertEquals(scenario.get("error").asText(), failure.getMessage());
                } else {
                    assertEquals(scenario.get("expected"), mapper.valueToTree(engine.evaluate(facts)));
                }
            }));
        }
        return tests;
    }
}
