package com.raidzon.match.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.match.domain.MatchEngine;
import com.raidzon.match.dto.CreateMatchRequest;
import com.raidzon.match.dto.MatchEventRequest;
import com.raidzon.match.dto.MatchJsonCodec;
import com.raidzon.match.service.MatchService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, isolated random schemas. Never runs against a database unless explicitly configured. */
@EnabledIfEnvironmentVariable(named = "RAIDZON_TEST_DATABASE_URL", matches = ".+")
class PostgresMatchStoreTest {
    final ObjectMapper json = new ObjectMapper();
    final UUID owner = UUID.randomUUID(), session = UUID.randomUUID();
    JdbcTemplate jdbc;
    MatchService service;
    MatchRepository repository;
    TransactionTemplate transaction;
    MatchJsonCodec codec;
    CreateMatchRequest setup;

    @BeforeEach void database() {
        var data = new PGSimpleDataSource();
        data.setURL(System.getenv("RAIDZON_TEST_DATABASE_URL"));
        data.setUser(System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_USERNAME", "raidzon_test"));
        data.setPassword(System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD", ""));
        String schema = "test_match_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(data).execute("CREATE SCHEMA " + schema);
        data.setCurrentSchema(schema);
        Flyway.configure().dataSource(data).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(data);
        codec = new MatchJsonCodec(json);
        repository = new MatchRepository(jdbc, codec);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
        service = new MatchService(repository, codec, transaction);
        var teams = IntStream.range(0, 2).mapToObj(side -> new CreateMatchRequest.TeamRoster("Team " + side,
                IntStream.range(0, 7).mapToObj(index -> new CreateMatchRequest.Player(UUID.randomUUID(), "Player " + side + index,
                        "+9198765432" + side + index)).toList())).toList();
        setup = new CreateMatchRequest(UUID.randomUUID(), teams, 0, 20, 30, 1000);
        service.create(setup, owner, session);
    }
    MatchEventRequest event(int version, int side) throws Exception {
        return new MatchEventRequest(UUID.randomUUID(), version, MatchEngine.RULESET_VERSION,
                2000 + version * 1000L, json.readTree("{\"type\":\"TECHNICAL\",\"side\":" + side + "}"));
    }
    int count() { return jdbc.queryForObject("SELECT count(*) FROM match_events", Integer.class); }

    @Test void migrationAndSetupRetriesAreSafe() {
        assertEquals(List.of(0, 0), service.create(setup, owner, session).state().scores());
        assertTrue(service.create(setup, owner, session).duplicate());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM matches", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> service.create(new CreateMatchRequest(setup.matchId(), setup.teams(), 1, 20, 30, 1000), owner, session));
        assertThrows(SecurityException.class, () -> service.create(setup, UUID.randomUUID(), session));
    }
    @Test void legacyRegistrationReplayAndRetriesKeepTheirRuleset() throws Exception {
        var legacy = new CreateMatchRequest(UUID.randomUUID(), setup.teams(), 0, 20, 30, 1000, "raidzon-v2");
        assertEquals("raidzon-v2", service.create(legacy, owner, session).rulesetVersion());
        var request = new MatchEventRequest(UUID.randomUUID(), 0, "raidzon-v2", 2000,
                json.readTree("{\"type\":\"TECHNICAL\",\"side\":0}"));
        service.append(legacy.matchId(), request, owner, session);
        assertTrue(service.append(legacy.matchId(), request, owner, session).duplicate());
        assertTrue(service.create(legacy, owner, session).duplicate());
        assertEquals(List.of(1, 0), service.rebuild(legacy.matchId(), owner, session).scores());
        var mixed = new MatchEventRequest(UUID.randomUUID(), 1, "raidzon-v3", 3000, request.intent());
        assertThrows(IllegalArgumentException.class, () -> service.append(legacy.matchId(), mixed, owner, session));
        assertThrows(IllegalArgumentException.class, () -> service.create(new CreateMatchRequest(legacy.matchId(), legacy.teams(), 0, 20, 30, 1000, "raidzon-v3"), owner, session));
    }

    @Test void lostAcknowledgementRetryReturnsOriginalSequenceAndCurrentState() throws Exception {
        var first = event(0, 0); service.append(setup.matchId(), first, owner, session);
        service.append(setup.matchId(), event(1, 1), owner, session);
        var retry = service.append(setup.matchId(), first, owner, session);
        assertTrue(retry.duplicate()); assertEquals(1, retry.acceptedVersion()); assertEquals(2, retry.currentVersion());
        assertEquals(List.of(1, 1), retry.state().scores()); assertEquals(2, count());
        assertEquals(2, service.create(setup, owner, session).version());
        var reordered = new MatchEventRequest(first.id(), 0, first.rulesetVersion(), first.occurredAt(), json.readTree("{\"side\":0,\"type\":\"TECHNICAL\"}"));
        assertTrue(service.append(setup.matchId(), reordered, owner, session).duplicate());
    }
    @Test void changedIdFactsAndStaleVersionsAreRejected() throws Exception {
        var first = event(0, 0); service.append(setup.matchId(), first, owner, session);
        var changed = new MatchEventRequest(first.id(), 0, first.rulesetVersion(), first.occurredAt(), json.readTree("{\"type\":\"TECHNICAL\",\"side\":1}"));
        assertThrows(IllegalArgumentException.class, () -> service.append(setup.matchId(), changed, owner, session));
        var stale = event(0, 1);
        assertThrows(IllegalArgumentException.class, () -> service.append(setup.matchId(), stale, owner, session));
        assertEquals(1, count());
    }
    @Test void wrongAccountOrDeviceCannotWriteOrRetry() throws Exception {
        var request = event(0, 0);
        assertThrows(SecurityException.class, () -> service.append(setup.matchId(), request, UUID.randomUUID(), session));
        assertThrows(SecurityException.class, () -> service.append(setup.matchId(), request, owner, UUID.randomUUID()));
        assertEquals(0, count());
        service.append(setup.matchId(), request, owner, session);
        assertThrows(SecurityException.class, () -> service.append(setup.matchId(), request, owner, UUID.randomUUID()));
        assertEquals(1, count());
    }
    @Test void failedProjectionWriteRollsBackEventInsert() throws Exception {
        jdbc.execute("CREATE FUNCTION fail_projection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test projection failure'; END; $$");
        jdbc.execute("CREATE TRIGGER fail_projection BEFORE UPDATE ON matches FOR EACH ROW EXECUTE FUNCTION fail_projection()");
        var request = event(0, 0);
        assertThrows(DataAccessException.class, () -> service.append(setup.matchId(), request, owner, session));
        assertEquals(0, count()); assertEquals(0, jdbc.queryForObject("SELECT version FROM matches", Integer.class));
    }
    @Test void replayAfterServiceRestartAndUndoPreserveHistory() throws Exception {
        service.append(setup.matchId(), event(0, 0), owner, session);
        var second = event(1, 1); service.append(setup.matchId(), second, owner, session);
        var undo = new MatchEventRequest(UUID.randomUUID(), 2, MatchEngine.RULESET_VERSION, 4000,
                json.readTree("{\"type\":\"UNDO\",\"targetEventId\":\"" + second.id() + "\"}"));
        var result = service.append(setup.matchId(), undo, owner, session);
        var restarted = new MatchService(new MatchRepository(jdbc, new MatchJsonCodec(json)), new MatchJsonCodec(json), transaction);
        assertEquals(result.state(), restarted.rebuild(setup.matchId(), owner, session));
        assertEquals(List.of(1, 0), result.state().scores()); assertEquals(3, count());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE match_events SET summary = 'changed'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM match_events"));
        assertEquals(3, count());
    }
    @Test void concurrentWritersAtSameVersionAcceptExactlyOne() throws Exception {
        var first = event(0, 0); var second = event(0, 1);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(first, second).stream().map(request -> pool.submit(() -> {
                gate.await();
                try { service.append(setup.matchId(), request, owner, session); return 1; }
                catch (IllegalArgumentException conflict) { return 0; }
            })).toList();
            gate.countDown();
            assertEquals(1, tasks.get(0).get(10, TimeUnit.SECONDS) + tasks.get(1).get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count()); assertEquals(1, jdbc.queryForObject("SELECT version FROM matches", Integer.class));
    }
    @Test void untrustedFieldsAndCoercedFactsAreRejectedWithoutWrites() throws Exception {
        for (var intent : List.of("{\"type\":\"TECHNICAL\",\"side\":0,\"scores\":[99,0]}",
                "{\"type\":\"TECHNICAL\"}", "{\"type\":\"TECHNICAL\",\"side\":0.5}",
                "{\"type\":\"TECHNICAL\",\"side\":\"0\"}")) {
            var request = new MatchEventRequest(UUID.randomUUID(), 0, MatchEngine.RULESET_VERSION, 2000, json.readTree(intent));
            assertThrows(IllegalArgumentException.class, () -> service.append(setup.matchId(), request, owner, session));
        }
        assertEquals(0, count());
    }
    @Test void changedProjectionIsDetectedDuringReplay() throws Exception {
        service.append(setup.matchId(), event(0, 0), owner, session);
        jdbc.update("UPDATE matches SET projection = jsonb_set(projection, '{scores,0}', '99'::jsonb)");
        assertThrows(IllegalStateException.class, () -> service.rebuild(setup.matchId(), owner, session));
    }
}
