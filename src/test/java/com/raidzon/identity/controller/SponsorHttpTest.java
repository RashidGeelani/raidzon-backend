package com.raidzon.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Sponsors: organizer-only changes, limits, public list and logo, admin take-down; short links: idempotent, resolve, public only. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("postgres") @Import(AuthHttpTest.SmsConfig.class)
@EnabledIfEnvironmentVariable(named = "RAIDZON_TEST_DATABASE_URL", matches = ".+")
class SponsorHttpTest {
    static final String ADMIN = "+919990001234";
    @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired AuthHttpTest.TestSms sms;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("RAIDZON_TEST_DATABASE_URL"), user = System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_USERNAME", "raidzon_test"), password = System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD", "");
        var data = new PGSimpleDataSource(); data.setURL(url); data.setUser(user); data.setPassword(password);
        String schema = "test_promo_" + UUID.randomUUID().toString().replace("-", ""); new JdbcTemplate(data).execute("CREATE SCHEMA " + schema);
        properties.add("raidzon.database.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        properties.add("raidzon.database.username", () -> user); properties.add("raidzon.database.password", () -> password);
        properties.add("raidzon.auth.pepper", () -> "test-only-pepper-that-is-at-least-32-characters");
        properties.add("RAIDZON_ADMIN_PHONES", () -> ADMIN);
    }
    String phone() { return "+91" + String.format(Locale.ROOT, "%010d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 10_000_000_000L)); }
    String login(String phone) throws Exception {
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]); UUID device = UUID.randomUUID();
        var challenge = send("/auth/challenges", Map.of("phone", phone, "deviceId", device, "deviceSecret", secret), null, 200);
        return send("/auth/verify", Map.of("challengeId", challenge.get("challengeId").asText(), "code", sms.codes.get(phone), "deviceId", device, "deviceSecret", secret), null, 200).get("token").asText();
    }
    JsonNode send(String path, Object body, String token, int status) throws Exception {
        var request = post("/api/v1" + path).contentType("application/json").content(json.writeValueAsBytes(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        String text = http.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(text.isEmpty() ? "null" : text);
    }
    JsonNode read(String path, String token, int status) throws Exception {
        var request = get("/api/v1" + path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json.readTree(http.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    UUID tournament(String owner) throws Exception {
        UUID id = UUID.randomUUID();
        send("/tournaments", Map.of("id", id, "name", "Valley Cup", "venue", "Sopore", "startsOn", "2026-11-10", "halfMinutes", 20, "raidSeconds", 30), owner, 200);
        return id;
    }
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0};
    static final String LOGO = Base64.getEncoder().encodeToString(PNG);
    Map<String, Object> sponsor(UUID id, String name, String level) {
        var body = new HashMap<String, Object>(Map.of("id", id, "name", name, "level", level, "logo", LOGO));
        body.put("link", "https://example.in");
        return body;
    }

    @Test void organizerManagesSponsorsWithinTheLimits() throws Exception {
        String owner = login(phone()), stranger = login(phone());
        UUID t = tournament(owner);
        UUID title = UUID.randomUUID();
        var list = send("/tournaments/" + t + "/sponsors", sponsor(title, "ABC Motors", "TITLE"), owner, 200);
        assertEquals(1, list.size());
        assertEquals("TITLE", list.get(0).get("level").asText());
        assertTrue(list.get(0).get("logoUrl").asText().startsWith("/api/v1/public/sponsors/" + title + "/logo?v="));

        // Only the organizer can change or even list them.
        send("/tournaments/" + t + "/sponsors", sponsor(UUID.randomUUID(), "Sneaky", "PARTNER"), stranger, 404);
        read("/tournaments/" + t + "/sponsors", stranger, 404);
        send("/tournaments/" + t + "/sponsors/" + title + "/remove", Map.of(), stranger, 404);

        // One Title sponsor, four in all.
        send("/tournaments/" + t + "/sponsors", sponsor(UUID.randomUUID(), "Second Title", "TITLE"), owner, 422);
        for (int i = 0; i < 3; i++) send("/tournaments/" + t + "/sponsors", sponsor(UUID.randomUUID(), "Partner " + i, "PARTNER"), owner, 200);
        send("/tournaments/" + t + "/sponsors", sponsor(UUID.randomUUID(), "Fifth", "PARTNER"), owner, 422);

        // Editing keeps the logo when none is sent; the logo URL changes when it is replaced.
        var edit = new HashMap<String, Object>(Map.of("id", title, "name", "ABC Motors Sopore", "level", "TITLE"));
        var edited = send("/tournaments/" + t + "/sponsors", edit, owner, 200);
        assertEquals("ABC Motors Sopore", edited.get(0).get("name").asText());
        assertTrue(edited.get(0).get("link").isNull());

        // Bad input.
        var script = sponsor(UUID.randomUUID(), "X", "PARTNER"); script.put("link", "javascript:alert(1)");
        send("/tournaments/" + t + "/sponsors/" + UUID.randomUUID() + "/remove", Map.of(), owner, 404);
        send("/tournaments/" + t + "/sponsors/" + edited.get(1).get("id").asText() + "/remove", Map.of(), owner, 200);
        send("/tournaments/" + t + "/sponsors", script, owner, 422);
        var svg = sponsor(UUID.randomUUID(), "X", "PARTNER"); svg.put("logo", Base64.getEncoder().encodeToString("<svg/>".getBytes()));
        send("/tournaments/" + t + "/sponsors", svg, owner, 422);
        // A sponsor ID from another tournament can't be taken over.
        UUID other = tournament(owner);
        send("/tournaments/" + other + "/sponsors", sponsor(title, "Moved", "PARTNER"), owner, 422);
    }

    @Test void publicTournamentListsSponsorsAndServesLogosWithLongCaching() throws Exception {
        String owner = login(phone());
        UUID t = tournament(owner), id = UUID.randomUUID();
        send("/tournaments/" + t + "/sponsors", sponsor(id, "ABC Motors", "TITLE"), owner, 200);
        var detail = read("/public/tournaments/" + t, null, 200);
        assertEquals("ABC Motors", detail.get("sponsors").get(0).get("name").asText());
        assertFalse(detail.get("sponsors").get(0).has("logo"));
        var response = http.perform(get("/api/v1/public/sponsors/" + id + "/logo").header("Origin", "https://raidzon.com"))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("image/png", response.getContentType());
        assertArrayEquals(PNG, response.getContentAsByteArray());
        assertTrue(response.getHeader("Cache-Control").contains("max-age=31536000"));
        assertEquals("https://raidzon.com", response.getHeader("Access-Control-Allow-Origin"));
        read("/public/sponsors/" + UUID.randomUUID() + "/logo", null, 404);
    }

    @Test void adminCanTakeDownAnySponsor() throws Exception {
        String owner = login(phone()), admin = login(ADMIN);
        UUID t = tournament(owner), id = UUID.randomUUID();
        send("/tournaments/" + t + "/sponsors", sponsor(id, "Reported", "PARTNER"), owner, 200);
        send("/admin/sponsors/" + id + "/remove", Map.of(), owner, 403);
        send("/admin/sponsors/" + id + "/remove", Map.of(), admin, 200);
        assertEquals(0, read("/public/tournaments/" + t, null, 200).get("sponsors").size());
    }

    @Test void shortLinksAreIdempotentAndResolveOnlyPublicTargets() throws Exception {
        String owner = login(phone());
        UUID t = tournament(owner);
        var first = send("/public/short-links", Map.of("type", "TOURNAMENT", "id", t), null, 200);
        var again = send("/public/short-links", Map.of("type", "TOURNAMENT", "id", t), null, 200);
        String code = first.get("code").asText();
        assertTrue(code.matches("[2-9A-HJ-NP-Z]{5}"), code);
        assertEquals(code, again.get("code").asText());
        var resolved = read("/public/short-links/" + code.toLowerCase(Locale.ROOT), null, 200);
        assertEquals("TOURNAMENT", resolved.get("type").asText());
        assertEquals(t.toString(), resolved.get("id").asText());
        read("/public/short-links/ZZZZZ", null, 404);
        read("/public/short-links/O0O0O", null, 404);
        send("/public/short-links", Map.of("type", "TOURNAMENT", "id", UUID.randomUUID()), null, 404);
        // A match that isn't public gets no link.
        send("/public/short-links", Map.of("type", "MATCH", "id", UUID.randomUUID()), null, 404);
        send("/public/short-links", Map.of("type", "PLAYER", "id", t), null, 422);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM short_links WHERE target_id=?", Integer.class, t));
    }
}
