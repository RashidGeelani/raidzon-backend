package com.raidzon.match.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.match.dto.CreateMatchRequest;
import com.raidzon.match.dto.MatchJsonCodec;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Jersey numbers ride along in the match state; they never change the rules or older matches. */
class JerseyNumberTest {
    private static CreateMatchRequest setup(Integer... jerseys) {
        var teams = IntStream.range(0, 2).mapToObj(side -> new CreateMatchRequest.TeamRoster("Team " + side,
            IntStream.range(0, 7).mapToObj(i -> new CreateMatchRequest.Player(UUID.nameUUIDFromBytes(("j" + side + i).getBytes()), "P" + side + i, "",
                jerseys.length == 0 ? null : jerseys[i])).toList())).toList();
        return new CreateMatchRequest(UUID.randomUUID(), teams, 0, 20, 30, 1_000, MatchEngine.V5);
    }

    @Test void jerseysAreKeptThroughScoring() {
        var state = setup(7, 10, 0, 999, 23, 5, 12).initialState();
        assertEquals(999, state.teams().get(0).players().get(3).jersey());
        var engine = new MatchEngine(MatchEngine.V5);
        String raider = state.teams().get(0).players().get(0).id();
        state = engine.apply(state, new MatchAction.StartRaid(raider), 2_000).state();
        state = engine.apply(state, new MatchAction.Raid(raider, "TOUCH", List.of(state.teams().get(1).players().get(0).id()), List.of(), null, false), 3_000).state();
        assertEquals(7, state.teams().get(0).players().get(0).jersey());
        assertEquals(MatchState.PlayerStatus.OUT, state.teams().get(1).players().get(0).status());
        assertEquals(7, state.teams().get(1).players().get(0).jersey());
    }

    @Test void numbersAreCheckedWhenGiven() {
        assertThrows(IllegalArgumentException.class, () -> setup(7, 7, 1, 2, 3, 4, 5).initialState());
        assertThrows(IllegalArgumentException.class, () -> setup(1000, 1, 2, 3, 4, 5, 6).initialState());
        assertThrows(IllegalArgumentException.class, () -> setup(-1, 1, 2, 3, 4, 5, 6).initialState());
    }

    @Test void olderMatchesWithoutNumbersAreUnchanged() throws Exception {
        var json = new ObjectMapper(); var codec = new MatchJsonCodec(json);
        var request = setup();
        var state = request.initialState();
        assertNull(state.teams().get(0).players().get(0).jersey());
        // No "jersey" key anywhere: stored states and creation fingerprints stay byte-for-byte the same.
        assertFalse(codec.write(state).contains("jersey"));
        assertFalse(json.writeValueAsString(request).contains("jersey"));
        assertEquals(state, codec.read(codec.write(state), MatchState.class));
        var numbered = setup(1, 2, 3, 4, 5, 6, 7);
        assertTrue(codec.write(numbered.initialState()).contains("\"jersey\":1"));
        assertNotEquals(codec.fingerprint(request), codec.fingerprint(numbered));
    }
}
