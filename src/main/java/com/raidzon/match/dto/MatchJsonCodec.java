package com.raidzon.match.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raidzon.match.domain.MatchAction;
import com.raidzon.match.domain.MatchHistory;
import com.raidzon.match.dto.MatchEventRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;

/** Explicit fact decoding; polymorphic Java class names are never accepted from JSON. */
public final class MatchJsonCodec {
    private final ObjectMapper json;
    public MatchJsonCodec(ObjectMapper json) {
        this.json = json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.json.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Integer)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.String, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        this.json.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Boolean)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.String, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }

    public String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Cannot encode match data.", exception); }
    }
    public <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Stored match data cannot be decoded.", exception); }
    }
    public String fingerprint(Object value) {
        try {
            JsonNode tree = json.valueToTree(value);
            // Preserve pre-version-field creation hashes. Ruleset identity is checked separately.
            if (value instanceof CreateMatchRequest) ((ObjectNode) tree).remove("rulesetVersion");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(tree).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Cannot fingerprint match data.", exception); }
    }
    private String canonical(JsonNode node) {
        if (node.isObject()) {
            var entries = new TreeMap<String, String>();
            node.fields().forEachRemaining(entry -> entries.put(entry.getKey(), canonical(entry.getValue())));
            return "{" + String.join(",", entries.entrySet().stream().map(entry -> write(entry.getKey()) + ":" + entry.getValue()).toList()) + "}";
        }
        if (node.isArray()) {
            var values = new java.util.ArrayList<String>(); node.forEach(value -> values.add(canonical(value)));
            return "[" + String.join(",", values) + "]";
        }
        return node.toString();
    }

    public MatchHistory.Input input(MatchEventRequest request) {
        ObjectNode intent = (ObjectNode) request.intent();
        String type = intent.path("type").asText();
        MatchAction action;
        try {
            action = switch (type) {
                case "START_RAID" -> decode(intent, MatchAction.StartRaid.class, "raiderId");
                case "RAID" -> decode(intent, MatchAction.Raid.class, "raiderId", "outcome", "defenderIds", "bonus");
                case "TECHNICAL" -> decode(intent, MatchAction.Technical.class, "side");
                case "DEFENDER_SELF_OUT" -> decode(intent, MatchAction.DefenderSelfOut.class, "playerId");
                case "SUBSTITUTE" -> decode(intent, MatchAction.Substitute.class, "side", "outgoingId", "incomingId");
                case "TIE_BREAK" -> decode(intent, MatchAction.TieBreak.class, "raiderIds");
                case "UNDO" -> decode(intent, MatchAction.Undo.class, "targetEventId");
                default -> {
                    if (intent.size() != 1) throw new IllegalArgumentException("Unexpected lifecycle fields.");
                    yield MatchAction.Lifecycle.valueOf(type);
                }
            };
        } catch (Exception exception) { throw new IllegalArgumentException("Invalid event intent.", exception); }
        return new MatchHistory.Input(request.id().toString(), request.baseVersion(), request.rulesetVersion(), request.occurredAt(), action);
    }
    private <T extends MatchAction> T decode(ObjectNode source, Class<T> type, String... required) throws Exception {
        for (var name : required) if (!source.hasNonNull(name)) throw new IllegalArgumentException("Missing " + name);
        var fields = new java.util.HashSet<String>(Set.of("type"));
        for (var component : type.getRecordComponents()) fields.add(component.getName());
        source.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw new IllegalArgumentException("Unexpected field " + name); });
        var payload = source.deepCopy(); payload.remove("type");
        return json.treeToValue(payload, type);
    }
}
