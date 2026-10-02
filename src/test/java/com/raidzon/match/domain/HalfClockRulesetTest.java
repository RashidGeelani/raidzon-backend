package com.raidzon.match.domain;

import com.raidzon.match.dto.CreateMatchRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** raidzon-v4: each half's match clock starts with that half's first raid. v3 is unchanged. */
class HalfClockRulesetTest {
    private static final long HALF = 20 * 60_000L;

    private static CreateMatchRequest setup(String ruleset) {
        var teams = IntStream.range(0, 2).mapToObj(side -> new CreateMatchRequest.TeamRoster("Team " + side,
            IntStream.range(0, 7).mapToObj(i -> new CreateMatchRequest.Player(UUID.nameUUIDFromBytes(("p" + side + i).getBytes()), "P" + side + i,
                "+9198765432" + side + i)).toList())).toList();
        return new CreateMatchRequest(UUID.randomUUID(), teams, 0, 20, 30, 1_000, ruleset);
    }
    private static String raider(MatchState state) { return state.teams().get(state.turn()).players().getFirst().id(); }

    @Test void v4ClockWaitsForTheFirstRaidOfEachHalf() {
        var engine = new MatchEngine(MatchEngine.V4);
        var state = setup(MatchEngine.V4).initialState();
        assertNull(state.clock().startedAt());
        assertEquals(HALF, state.clock().remaining(500_000));

        // Pausing and resuming before the first raid keeps the clock waiting.
        state = engine.apply(state, MatchAction.Lifecycle.PAUSE, 2_000).state();
        state = engine.apply(state, MatchAction.Lifecycle.RESUME, 3_000).state();
        assertNull(state.clock().startedAt());

        state = engine.apply(state, new MatchAction.StartRaid(raider(state)), 10_000).state();
        assertEquals(10_000L, state.clock().startedAt());
        assertEquals(HALF - 5_000, state.clock().remaining(15_000));

        // Later raids do not restart the clock.
        state = engine.apply(state, new MatchAction.Raid(raider(state), "EMPTY", List.of(), List.of(), null, false), 20_000).state();
        state = engine.apply(state, new MatchAction.StartRaid(raider(state)), 30_000).state();
        assertEquals(10_000L, state.clock().startedAt());
        state = engine.apply(state, new MatchAction.Raid(raider(state), "EMPTY", List.of(), List.of(), null, false), 40_000).state();

        // Pause/resume after the clock started behaves as before.
        state = engine.apply(state, MatchAction.Lifecycle.PAUSE, 50_000).state();
        assertEquals(HALF - 40_000, state.clock().remainingMs());
        state = engine.apply(state, MatchAction.Lifecycle.RESUME, 60_000).state();
        assertEquals(60_000L, state.clock().startedAt());

        state = engine.apply(state, MatchAction.Lifecycle.END_HALF, 70_000).state();
        state = engine.apply(state, MatchAction.Lifecycle.SECOND_HALF, 400_000).state();
        assertNull(state.clock().startedAt());
        assertEquals(HALF, state.clock().remainingMs());
        state = engine.apply(state, new MatchAction.StartRaid(raider(state)), 450_000).state();
        assertEquals(450_000L, state.clock().startedAt());
    }

    @Test void v3StillStartsTheClockAtSetupAndHalfTime() {
        var engine = new MatchEngine(MatchEngine.RULESET_VERSION);
        var state = setup(MatchEngine.RULESET_VERSION).initialState();
        assertEquals(1_000L, state.clock().startedAt());
        state = engine.apply(state, MatchAction.Lifecycle.END_HALF, 70_000).state();
        state = engine.apply(state, MatchAction.Lifecycle.SECOND_HALF, 400_000).state();
        assertEquals(400_000L, state.clock().startedAt());
    }

    /** The server applies taps to the stored projection; it must match a full replay exactly. */
    @Test void steppingTheProjectionMatchesReplayingTheHistory() {
        for (String ruleset : List.of(MatchEngine.RULESET_VERSION, MatchEngine.V4)) {
            var history = MatchHistory.fromSnapshot(setup(ruleset).initialState());
            var state = history.state();
            long now = 5_000;
            for (int i = 0; i < 12; i++) {
                String raiderId = raider(history.state());
                MatchAction action = i % 2 == 0 ? new MatchAction.StartRaid(raiderId)
                    : new MatchAction.Raid(raiderId, "EMPTY", List.of(), List.of(), null, false);
                var input = new MatchHistory.Input("e" + i, history.version(), ruleset, now += 7_000, action);
                var step = MatchHistory.step(state, history.version(), input);
                history = history.append(input);
                assertEquals(history.events().getLast(), step);
                state = step.result().state();
            }
            assertEquals(history.rebuild().state(), state);
        }
    }
}
