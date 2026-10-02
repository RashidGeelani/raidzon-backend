package com.raidzon.tournament.service;

import com.raidzon.tournament.dto.Tournament;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TournamentFormatsTest {
    private static List<UUID> ids(int n) { return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList(); }

    @Test void roundRobinPlaysEveryPairOnceWithOneMatchPerTeamPerRound() {
        for (int n : new int[] {2, 3, 4, 5, 6, 7, 8}) {
            var teams = ids(n);
            var plan = TournamentFormats.roundRobin(teams, "LEAGUE", null);
            assertEquals(n * (n - 1) / 2, plan.size());
            var pairs = new HashSet<Set<UUID>>();
            for (var f : plan) assertTrue(pairs.add(Set.of(f.teamA(), f.teamB())));
            var perRound = new HashMap<Integer, Set<UUID>>();
            for (var f : plan) {
                var seen = perRound.computeIfAbsent(f.round(), r -> new HashSet<>());
                assertTrue(seen.add(f.teamA()));
                assertTrue(seen.add(f.teamB()));
            }
        }
    }

    @Test void knockoutOfEightHasQuartersSemisFinalAndOptionalThirdPlace() {
        var teams = ids(8).stream().map(TournamentFormats::team).toList();
        var plan = TournamentFormats.knockout(TournamentFormats.seededPairs(teams), true);
        assertEquals(4, plan.stream().filter(f -> f.round() == 1).count());
        assertEquals(2, plan.stream().filter(f -> f.round() == 2).count());
        assertEquals(1, plan.stream().filter(f -> "KNOCKOUT".equals(f.stage()) && f.round() == 3).count());
        assertEquals(1, plan.stream().filter(f -> "THIRD_PLACE".equals(f.stage())).count());
        // Seeds 1 and 2 are in opposite halves.
        var first = plan.stream().filter(f -> f.round() == 1).toList();
        assertEquals(teams.get(0), TournamentFormats.team(first.get(0).teamA()));
        assertEquals(teams.get(1), TournamentFormats.team(first.get(2).teamA()));
    }

    @Test void oddKnockoutGivesTopSeedsByes() {
        var teams = ids(6).stream().map(TournamentFormats::team).toList();
        var plan = TournamentFormats.knockout(TournamentFormats.seededPairs(teams), false);
        assertEquals(2, plan.stream().filter(f -> f.round() == 1).count()); // 3v6, 4v5; seeds 1 and 2 bye
        assertEquals(5, plan.size()); // 2 + 2 semis + final
        var semis = plan.stream().filter(f -> f.round() == 2).toList();
        assertTrue(semis.stream().anyMatch(f -> f.teamA() != null && TournamentFormats.team(f.teamA()).equals(teams.get(0))));
    }

    @Test void groupQualifiersFromTheSameGroupMeetOnlyInTheFinal() {
        var groups = ids(4);
        var pairs = TournamentFormats.groupPairs(groups, 2);
        assertEquals(4, pairs.size());
        for (var pair : pairs) assertTrue(!pair[0].split(":")[1].equals(pair[1].split(":")[1]));
        // A1 in the top half, B1 in the bottom half.
        assertEquals(TournamentFormats.groupPlace(groups.get(0), 1), pairs.get(0)[0]);
        assertEquals(TournamentFormats.groupPlace(groups.get(1), 1), pairs.get(2)[0]);
    }

    @Test void validatesQualifierCounts() {
        assertNull(TournamentFormats.validate(new Tournament.FormatInput("GROUPS_KNOCKOUT", 2, 2, false)));
        assertNull(TournamentFormats.validate(new Tournament.FormatInput("GROUPS_KNOCKOUT", 1, 4, true)));
        assertTrue(TournamentFormats.validate(new Tournament.FormatInput("GROUPS_KNOCKOUT", 3, 2, false)) != null);
        assertTrue(TournamentFormats.validate(new Tournament.FormatInput("GROUPS_KNOCKOUT", 1, 1, false)) != null);
        assertTrue(TournamentFormats.validate(new Tournament.FormatInput("CUP", 1, 1, false)) != null);
    }

    @Test void resolvesWinnersIntoTheNextRoundButNotFromADraw() {
        var a = UUID.randomUUID(); var b = UUID.randomUUID(); var c = UUID.randomUUID(); var d = UUID.randomUUID();
        var plan = TournamentFormats.knockout(TournamentFormats.seededPairs(List.of(a, b, c, d).stream().map(TournamentFormats::team).toList()), false);
        var fixtures = new ArrayList<Tournament.Fixture>();
        for (var p : plan) {
            boolean firstSemi = p.round() == 1 && p.slot() == 0;
            boolean secondSemi = p.round() == 1 && p.slot() == 1;
            fixtures.add(new Tournament.Fixture(p.id(), p.teamA(), p.teamB(), null, firstSemi || secondSemi ? UUID.randomUUID() : null,
                firstSemi || secondSemi ? "COMPLETED" : null, 10, 10, "REGULATION", 0, 0, firstSemi ? "TEAM_B" : secondSemi ? "DRAW" : null, 0,
                p.stage(), null, p.round(), p.slot(), p.sourceA(), p.sourceB(), null, null, null));
        }
        Map<UUID, String> names = Map.of(a, "A", b, "B", c, "C", d, "D");
        var resolved = TournamentFormats.resolve(fixtures, names, Map.of(), Map.of(), Set.of());
        var fin = resolved.stream().filter(f -> f.round() == 2).findFirst().orElseThrow();
        assertEquals("Final", fin.roundName());
        assertEquals(d, fin.teamAId()); // seed 4 beat seed 1
        assertNull(fin.teamBId());      // the other semi was drawn: tie-break still needed
        assertEquals("Winner SF 2", fin.labelB());
        assertNull(TournamentFormats.champion(resolved));
    }
}
