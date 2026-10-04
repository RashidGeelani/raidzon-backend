package com.raidzon.match.domain;

import com.raidzon.match.dto.CreateMatchRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** raidzon-v5 Do-or-Die: the third raid after two empty raids by a team must score or the raider is OUT. */
class DoOrDieRulesetTest {
    private static CreateMatchRequest setup(String ruleset) {
        var teams = IntStream.range(0, 2).mapToObj(side -> new CreateMatchRequest.TeamRoster("Team " + side,
            IntStream.range(0, 7).mapToObj(i -> new CreateMatchRequest.Player(UUID.nameUUIDFromBytes(("d" + side + i).getBytes()), "P" + side + i,
                "+9198765433" + side + i)).toList())).toList();
        return new CreateMatchRequest(UUID.randomUUID(), teams, 0, 20, 30, 1_000, ruleset);
    }
    private static String raider(MatchState state) {
        return state.teams().get(state.turn()).players().stream().filter(p -> p.status() == MatchState.PlayerStatus.ACTIVE).findFirst().orElseThrow().id();
    }
    private static String defender(MatchState state) {
        return state.teams().get(1 - state.turn()).players().stream().filter(p -> p.status() == MatchState.PlayerStatus.ACTIVE).findFirst().orElseThrow().id();
    }
    private long now = 10_000;
    private MatchTransition raid(MatchEngine engine, MatchState state, String outcome) {
        String id = raider(state);
        state = engine.apply(state, new MatchAction.StartRaid(id), now += 1_000).state();
        var touched = outcome.equals("TOUCH") ? List.of(defender(state)) : List.<String>of();
        return engine.apply(state, new MatchAction.Raid(id, outcome, touched, List.of(), null, false), now += 1_000);
    }

    @Test void thirdEmptyRaidPutsTheRaiderOutAndScoresForTheDefenders() {
        var engine = new MatchEngine(MatchEngine.V5);
        var state = setup(MatchEngine.V5).initialState();
        assertNull(state.emptyRaids(), "older-format snapshot until the first v5 event");
        for (int round = 0; round < 2; round++) {
            state = raid(engine, state, "EMPTY").state(); // team 0
            state = raid(engine, state, "EMPTY").state(); // team 1
        }
        assertEquals(List.of(2, 2), state.emptyRaids());
        assertTrue(state.doOrDie());
        String raiderId = raider(state);
        var failed = raid(engine, state, "EMPTY");
        state = failed.state();
        assertEquals(List.of(0, 1), state.scores());
        assertEquals(MatchState.PlayerStatus.OUT, state.teams().get(0).players().stream().filter(p -> p.id().equals(raiderId)).findFirst().orElseThrow().status());
        assertTrue(failed.summary().endsWith(": do-or-die raid failed"));
        assertEquals(List.of(0, 2), state.emptyRaids());
        // Team 1 is now on Do-or-Die; a scoring raid resets its count.
        assertTrue(state.doOrDie());
        state = raid(engine, state, "TOUCH").state();
        assertEquals(List.of(0, 0), state.emptyRaids());
        assertFalse(state.doOrDie());
    }

    @Test void secondHalfStartsFresh() {
        var engine = new MatchEngine(MatchEngine.V5);
        var state = setup(MatchEngine.V5).initialState();
        state = raid(engine, state, "EMPTY").state();
        state = raid(engine, state, "EMPTY").state();
        state = engine.apply(state, MatchAction.Lifecycle.END_HALF, now += 1_000).state();
        state = engine.apply(state, MatchAction.Lifecycle.SECOND_HALF, now += 1_000).state();
        assertEquals(List.of(0, 0), state.emptyRaids());
    }

    @Test void olderRulesetsNeverCountEmptyRaids() {
        var engine = new MatchEngine(MatchEngine.V4);
        var state = setup(MatchEngine.V4).initialState();
        for (int i = 0; i < 6; i++) state = raid(engine, state, "EMPTY").state();
        assertNull(state.emptyRaids());
        assertEquals(List.of(0, 0), state.scores());
    }
}
