package com.raidzon.match.domain;

import com.raidzon.match.dto.CreateMatchRequest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Quick matches may leave player phones blank; given phones stay canonical and unique. */
class OptionalPhoneTest {
    private static CreateMatchRequest setup(String blankOr) {
        var teams = IntStream.range(0, 2).mapToObj(side -> new CreateMatchRequest.TeamRoster("Team " + side,
            IntStream.range(0, 7).mapToObj(i -> new CreateMatchRequest.Player(UUID.randomUUID(), "P" + side + i,
                i < 2 ? blankOr : "+9198765432" + side + i)).toList())).toList();
        return new CreateMatchRequest(UUID.randomUUID(), teams, 0, 20, 30, 1_000, MatchEngine.V4);
    }

    @Test void blankAndMissingPhonesAreAllowed() {
        var state = setup("").initialState();
        assertEquals(4, state.teams().stream().flatMap(team -> team.players().stream()).filter(p -> p.phone().isEmpty()).count());
        var missing = setup(null).initialState();
        assertEquals("", missing.teams().getFirst().players().getFirst().phone());
    }

    @Test void givenPhonesMustStillBeCanonicalAndUnique() {
        assertThrows(IllegalArgumentException.class, () -> setup("98765").initialState());
        assertThrows(IllegalArgumentException.class, () -> setup("+919876543299").initialState());
    }
}
