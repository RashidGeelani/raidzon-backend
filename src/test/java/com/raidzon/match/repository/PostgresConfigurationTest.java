package com.raidzon.match.repository;

import com.raidzon.match.service.MatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "RAIDZON_TEST_DATABASE_URL", matches = ".+")
class PostgresConfigurationTest {
    @Autowired MatchService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.raidzon.identity.service.AuthService auth;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("RAIDZON_TEST_DATABASE_URL");
        String user = System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_USERNAME", "raidzon_test");
        String password = System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD", "");
        var data = new PGSimpleDataSource(); data.setURL(url); data.setUser(user); data.setPassword(password);
        String schema = "test_profile_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(data).execute("CREATE SCHEMA " + schema);
        properties.add("raidzon.database.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        properties.add("raidzon.database.username", () -> user);
        properties.add("raidzon.database.password", () -> password);
    }
    @Test void postgresProfileWiresServicePoolAndMigrations() {
        assertNotNull(service);
        assertFalse(auth.available());
        var failure = assertThrows(com.raidzon.identity.service.AuthFailure.class,
                () -> auth.request("+919876543210", UUID.randomUUID(), "x".repeat(43), "127.0.0.1"));
        assertEquals(503, failure.status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM matches", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
    }
}
