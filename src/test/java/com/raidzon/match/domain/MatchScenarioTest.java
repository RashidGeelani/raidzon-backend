package com.raidzon.match.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MatchScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode resource(String name) throws Exception {
        try (var stream = MatchScenarioTest.class.getResourceAsStream("/matches/" + name)) {
            assertNotNull(stream, name);
            return JSON.readTree(stream);
        }
    }

    private static MatchState initial() throws Exception {
        return JSON.treeToValue(resource("initial-state.json"), MatchState.class);
    }

    @TestFactory
    Stream<DynamicTest> sharedCompleteMatchScenarios() throws Exception {
        var scenarios = new ArrayList<JsonNode>();
        resource("match-scenarios.json").forEach(scenarios::add);
        return scenarios.stream().map(scenario -> DynamicTest.dynamicTest(scenario.path("name").asText(), () -> {
            var tree = resource("initial-state.json");
            scenario.path("initialOverrides").fields().forEachRemaining(entry -> {
                var pointer = entry.getKey();
                int split = pointer.lastIndexOf('/');
                var parent = tree.at(pointer.substring(0, split));
                var key = pointer.substring(split + 1);
                if (parent instanceof ArrayNode array) array.set(Integer.parseInt(key), entry.getValue());
                else ((ObjectNode) parent).set(key, entry.getValue());
            });
            var history = MatchHistory.fromSnapshot(JSON.treeToValue(tree, MatchState.class));
            for (var step : scenario.path("events")) {
                var prior = history;
                var input = new MatchHistory.Input(step.path("id").asText(), history.version(),
                        MatchEngine.RULESET_VERSION, step.path("at").asLong(), action(step.path("intent")));
                if (step.has("error")) {
                    var error = assertThrows(IllegalArgumentException.class, () -> prior.append(input));
                    assertEquals(step.path("error").asText(), error.getMessage(), input.id());
                    assertSame(prior, history);
                    continue;
                }
                history = history.append(input);
                assertEquals(prior.version() + 1, history.version());
                var event = history.events().getLast();
                var snapshot = JSON.createObjectNode();
                snapshot.set("before", stateJson(event.before()));
                snapshot.set("state", stateJson(history.state()));
                ArrayNode components = JSON.valueToTree(event.result().components());
                components.forEach(component -> {
                    if (component.path("playerId").isNull()) ((ObjectNode) component).remove("playerId");
                });
                snapshot.set("components", components);
                snapshot.put("summary", event.result().summary());
                step.path("expect").fields().forEachRemaining(entry ->
                        assertEquals(canonical(entry.getValue()), canonical(snapshot.path("state").at(entry.getKey())), input.id() + entry.getKey()));
                var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical(snapshot).getBytes(StandardCharsets.UTF_8)));
                assertEquals(step.path("expectedHash").asText(), hash, () -> input.id() + ": " + snapshot);
                var replayed = history.rebuild();
                assertEquals(history.state(), replayed.state(), "Replay state " + input.id());
                assertEquals(history.events(), replayed.events(), "Replay history " + input.id());
            }
        }));
    }

    private static ObjectNode stateJson(MatchState state) {
        ObjectNode node = JSON.valueToTree(state);
        if (state.winner() == MatchState.Winner.TEAM_A) node.put("winner", 0);
        if (state.winner() == MatchState.Winner.TEAM_B) node.put("winner", 1);
        return node;
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, String>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return "{" + String.join(",", sorted.entrySet().stream()
                    .map(entry -> JSON.getNodeFactory().textNode(entry.getKey()) + ":" + entry.getValue()).toList()) + "}";
        }
        if (node.isArray()) {
            var values = new ArrayList<String>();
            node.forEach(value -> values.add(canonical(value)));
            return "[" + String.join(",", values) + "]";
        }
        return node.toString();
    }

    private static List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static MatchAction action(JsonNode node) {
        return switch (node.path("type").asText()) {
            case "START_RAID" -> new MatchAction.StartRaid(node.path("raiderId").asText());
            case "RAID" -> new MatchAction.Raid(node.path("raiderId").asText(), node.path("outcome").asText(),
                    strings(node.path("defenderIds")), strings(node.path("selfOutDefenderIds")),
                    node.has("tacklerId") ? node.path("tacklerId").asText() : null, node.path("bonus").asBoolean(),
                    node.has("defenderOutOrder") ? strings(node.path("defenderOutOrder")) : null);
            case "TECHNICAL" -> new MatchAction.Technical(node.path("side").asInt());
            case "DEFENDER_SELF_OUT" -> new MatchAction.DefenderSelfOut(node.path("playerId").asText());
            case "SUBSTITUTE" -> new MatchAction.Substitute(node.path("side").asInt(), node.path("outgoingId").asText(), node.path("incomingId").asText());
            case "UNDO" -> new MatchAction.Undo(node.path("targetEventId").asText());
            case "TIE_BREAK" -> new MatchAction.TieBreak(List.of(strings(node.path("raiderIds").path(0)), strings(node.path("raiderIds").path(1))));
            default -> MatchAction.Lifecycle.valueOf(node.path("type").asText());
        };
    }

    @Test
    void lastRaiderTackleOverridesSelfOutRevivalButNormalPlayDoesNot() throws Exception {
        for (String ruleset : List.of("raidzon-v2", "raidzon-v3")) for (int active : List.of(1, 2)) {
            var tree = resource("initial-state.json");
            var queue = (ArrayNode) tree.at("/teams/0/queue");
            for (int i = 0; i < 7 - active; i++) {
                ((ObjectNode) tree.at("/teams/0/players/" + i)).put("status", "OUT");
                queue.add("0-" + i);
            }
            var history = MatchHistory.fromSnapshot(JSON.treeToValue(tree, MatchState.class));
            String raider = "0-" + (7 - active);
            history = history.append(new MatchHistory.Input("start", 0, ruleset, 1000,
                    new MatchAction.StartRaid(raider)));
            var before = history.state();
            history = history.append(new MatchHistory.Input("raid", 1, ruleset, 2000,
                    new MatchAction.Raid(raider, "TACKLE", List.of(), List.of("1-0"), "1-1", false)));
            var state = stateJson(history.state());
            assertEquals(1, state.at("/scores/0").asInt());
            assertEquals(active == 1 && ruleset.equals("raidzon-v3") ? 3 : 1, state.at("/scores/1").asInt());
            assertEquals(active == 1 ? (ruleset.equals("raidzon-v3") ? 0 : 6) : 5, state.at("/teams/0/queue").size());
            assertEquals(0, state.at("/teams/1/queue").size());
            assertEquals(history.state(), history.rebuild().state());
            history = history.append(new MatchHistory.Input("undo", 2, ruleset, 3000,
                    new MatchAction.Undo("raid")));
            assertEquals(before.teams(), history.state().teams());
            assertEquals(before.scores(), history.state().scores());
            assertEquals(before.currentRaiderId(), history.state().currentRaiderId());
        }
    }

    @Test
    void retriesAreIdempotentAndFactsCannotChange() throws Exception {
        var empty = MatchHistory.fromSnapshot(initial());
        var input = new MatchHistory.Input("one", 0, MatchEngine.RULESET_VERSION, 100, new MatchAction.Technical(0));
        var history = empty.append(input);
        assertSame(history, history.append(input));
        assertEquals(0, empty.version());
        assertThrows(IllegalArgumentException.class, () -> history.append(new MatchHistory.Input("one", 0, MatchEngine.RULESET_VERSION, 100, new MatchAction.Technical(1))));
        assertThrows(IllegalArgumentException.class, () -> history.append(new MatchHistory.Input("two", 0, MatchEngine.RULESET_VERSION, 101, new MatchAction.Technical(0))));
        assertThrows(IllegalArgumentException.class, () -> history.append(new MatchHistory.Input("two", 1, MatchEngine.RULESET_VERSION, 99, new MatchAction.Technical(0))));
        assertThrows(IllegalArgumentException.class, () -> new MatchHistory.Input("two", 1, "unknown", 101, new MatchAction.Technical(0)));
    }

    @Test
    void stateAndHistoryCollectionsAreImmutable() throws Exception {
        var state = initial();
        assertThrows(UnsupportedOperationException.class, () -> state.teams().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.teams().getFirst().players().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.teams().getFirst().queue().clear());
        assertThrows(UnsupportedOperationException.class, () -> MatchHistory.fromSnapshot(state).events().clear());
        var defenders = new ArrayList<>(List.of("1-0"));
        var action = new MatchAction.Raid("0-0", "TOUCH", defenders, List.of(), null, false);
        defenders.clear();
        assertEquals(List.of("1-0"), action.defenderIds());
    }

    @Test
    void backwardsClockDoesNotAddTime() {
        assertEquals(500, new MatchState.Clock(500, 100L).remaining(50));
        assertEquals(0, new MatchState.Clock(500, 100L).remaining(700));
    }
}
