package com.raidzon.tournament.service;

import com.raidzon.tournament.dto.Tournament;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fixture generation and knockout resolution for the three tournament formats.
 * Pure functions: no database access, so the rules are unit-tested directly.
 */
public final class TournamentFormats {
    private TournamentFormats() {}

    /** A fixture to insert. A side has either a team (teamA/teamB) or a source naming where its team comes from. */
    public record Planned(UUID id, String stage, UUID groupId, Integer round, Integer slot,
                          UUID teamA, UUID teamB, String sourceA, String sourceB) {}

    public static String team(UUID id) { return "T:" + id; }
    public static String groupPlace(UUID group, int rank) { return "G:" + group + ":" + rank; }
    public static String winner(UUID fixture) { return "W:" + fixture; }
    public static String loser(UUID fixture) { return "L:" + fixture; }

    /** Checks a format's settings before anything is generated; returns a message for the organizer or null. */
    public static String validate(Tournament.FormatInput input) {
        if (input == null || !Tournament.FORMATS.contains(input.type())) return "Choose League, Knockout or Groups + knockout.";
        if (!"GROUPS_KNOCKOUT".equals(input.type())) return null;
        int groups = input.groupCount(), advance = input.advancePerGroup();
        if (groups < 1 || groups > 8) return "Use 1 to 8 groups.";
        if (advance != 1 && advance != 2 && advance != 4) return "Choose 1, 2 or 4 teams to go through from each group.";
        if (advance == 1 && groups == 1) return "With one group, send at least 2 teams through.";
        if (advance == 4 && groups != 1) return "Four teams go through only when there is a single group.";
        int qualifiers = groups * advance;
        if (Integer.bitCount(qualifiers) != 1 || qualifiers > 16)
            return "Teams going through must make a full bracket (2, 4, 8 or 16). With " + groups + " groups choose "
                + (groups % 2 == 0 ? "1 or 2" : "a different group count") + ".";
        return null;
    }

    /** Round robin with the circle method: every pair meets once; rounds keep each team to one match per round. */
    public static List<Planned> roundRobin(List<UUID> teams, String stage, UUID group) {
        var list = new ArrayList<UUID>(teams);
        if (list.size() % 2 == 1) list.add(null); // bye
        int n = list.size();
        var result = new ArrayList<Planned>();
        for (int round = 0; round < n - 1; round++) {
            int slot = 0;
            for (int i = 0; i < n / 2; i++) {
                UUID a = list.get(i), b = list.get(n - 1 - i);
                if (a != null && b != null) {
                    // Alternate home side so no team is always "Team A".
                    boolean swap = (round + i) % 2 == 1;
                    result.add(new Planned(UUID.randomUUID(), stage, group, round + 1, slot++, swap ? b : a, swap ? a : b, null, null));
                }
            }
            list.add(1, list.remove(n - 1));
        }
        return result;
    }

    /** Standard seeding positions for a bracket of {@code size}: seed 1 and 2 can only meet in the final. */
    static int[] seedOrder(int size) {
        int[] order = {1, 2};
        while (order.length < size) {
            int next = order.length * 2;
            int[] expanded = new int[next];
            for (int i = 0; i < order.length; i++) {
                expanded[2 * i] = order[i];
                expanded[2 * i + 1] = next + 1 - order[i];
            }
            order = expanded;
        }
        return order;
    }

    /** First-round pairs for teams in seed order; a null opponent is a bye (the top seeds get them). */
    public static List<String[]> seededPairs(List<String> entrants) {
        int size = 2;
        while (size < entrants.size()) size <<= 1;
        int[] order = seedOrder(size);
        var pairs = new ArrayList<String[]>();
        for (int i = 0; i < size; i += 2) {
            String a = order[i] <= entrants.size() ? entrants.get(order[i] - 1) : null;
            String b = order[i + 1] <= entrants.size() ? entrants.get(order[i + 1] - 1) : null;
            pairs.add(new String[] {a, b});
        }
        return pairs;
    }

    /** Knockout qualifiers from groups, paired so teams from the same group meet as late as possible. */
    public static List<String[]> groupPairs(List<UUID> groups, int advance) {
        var pairs = new ArrayList<String[]>();
        if (advance == 1) {
            return seededPairs(groups.stream().map(g -> groupPlace(g, 1)).toList());
        }
        if (groups.size() == 1) {
            UUID g = groups.getFirst();
            if (advance == 2) pairs.add(new String[] {groupPlace(g, 1), groupPlace(g, 2)});
            else {
                pairs.add(new String[] {groupPlace(g, 1), groupPlace(g, 4)});
                pairs.add(new String[] {groupPlace(g, 2), groupPlace(g, 3)});
            }
            return pairs;
        }
        // advance 2 with an even number of groups: A1–B2 and C1–D2 in the top half, B1–A2 and D1–C2 below.
        var bottom = new ArrayList<String[]>();
        for (int i = 0; i + 1 < groups.size(); i += 2) {
            UUID x = groups.get(i), y = groups.get(i + 1);
            pairs.add(new String[] {groupPlace(x, 1), groupPlace(y, 2)});
            bottom.add(new String[] {groupPlace(y, 1), groupPlace(x, 2)});
        }
        pairs.addAll(bottom);
        return pairs;
    }

    /** Builds every knockout round from the first-round pairs; byes send a team straight to round two. */
    public static List<Planned> knockout(List<String[]> firstRound, boolean thirdPlace) {
        var result = new ArrayList<Planned>();
        // Each entry: the source of whoever comes out of that bracket position.
        var current = new ArrayList<String>();
        var realFixtures = new ArrayList<UUID>(); // fixture per position in the latest round, null when a bye
        int round = 1;
        int slot = 0;
        for (var pair : firstRound) {
            if (pair[0] != null && pair[1] != null) {
                var id = UUID.randomUUID();
                result.add(planned(id, "KNOCKOUT", round, slot, pair[0], pair[1]));
                current.add(winner(id));
                realFixtures.add(id);
            } else {
                current.add(pair[0] != null ? pair[0] : pair[1]);
                realFixtures.add(null);
            }
            slot++;
        }
        List<UUID> semis = null;
        while (current.size() > 1) {
            round++;
            var next = new ArrayList<String>();
            var nextFixtures = new ArrayList<UUID>();
            if (current.size() == 2) semis = realFixtures;
            for (int i = 0; i < current.size(); i += 2) {
                var id = UUID.randomUUID();
                result.add(planned(id, "KNOCKOUT", round, i / 2, current.get(i), current.get(i + 1)));
                next.add(winner(id));
                nextFixtures.add(id);
            }
            current = next;
            realFixtures = nextFixtures;
        }
        // With only two entrants the first round is the final; semis exist only when there were four or more.
        if (thirdPlace && semis != null && semis.size() == 2 && semis.get(0) != null && semis.get(1) != null)
            result.add(planned(UUID.randomUUID(), "THIRD_PLACE", round, 0, loser(semis.get(0)), loser(semis.get(1))));
        return result;
    }

    private static Planned planned(UUID id, String stage, int round, int slot, String a, String b) {
        UUID teamA = a.startsWith("T:") ? UUID.fromString(a.substring(2)) : null;
        UUID teamB = b.startsWith("T:") ? UUID.fromString(b.substring(2)) : null;
        return new Planned(id, stage, null, round, slot, teamA, teamB, teamA == null ? a : null, teamB == null ? b : null);
    }

    /** Knockout round name by how many teams are left in it. */
    public static String roundName(int round, int lastRound) {
        int teamsLeft = 1 << (lastRound - round + 1);
        return switch (teamsLeft) {
            case 2 -> "Final";
            case 4 -> "Semi-final";
            case 8 -> "Quarter-final";
            default -> "Round of " + teamsLeft;
        };
    }
    private static String shortRound(String name) {
        return switch (name) { case "Final" -> "Final"; case "Semi-final" -> "SF"; case "Quarter-final" -> "QF"; default -> name.replace("Round of ", "R"); };
    }

    /**
     * Fills in knockout teams that are decided (group finished, previous match won) and labels every side.
     * groupStandings: final table per group; groupsComplete: groups whose every match is completed.
     */
    public static List<Tournament.Fixture> resolve(List<Tournament.Fixture> fixtures, Map<UUID, String> teamNames,
                                                   Map<UUID, String> groupNames, Map<UUID, List<Tournament.Standing>> groupStandings,
                                                   java.util.Set<UUID> groupsComplete) {
        int lastRound = fixtures.stream().filter(f -> "KNOCKOUT".equals(f.stage()) && f.round() != null).mapToInt(Tournament.Fixture::round).max().orElse(0);
        var byId = new HashMap<UUID, Tournament.Fixture>();
        var labels = new HashMap<UUID, String>();
        for (var f : fixtures) {
            if ("KNOCKOUT".equals(f.stage()) && f.round() != null) {
                String name = roundName(f.round(), lastRound);
                long inRound = fixtures.stream().filter(o -> "KNOCKOUT".equals(o.stage()) && f.round().equals(o.round())).count();
                labels.put(f.id(), "Final".equals(name) ? "Final" : shortRound(name) + (inRound > 1 ? " " + (f.slot() + 1) : ""));
            } else if ("THIRD_PLACE".equals(f.stage())) labels.put(f.id(), "Third place");
        }
        var sorted = new ArrayList<>(fixtures);
        sorted.sort(java.util.Comparator.comparing((Tournament.Fixture f) -> f.knockout() ? 1 : 0)
            .thenComparing(f -> f.round() == null ? 0 : f.round()).thenComparing(f -> "THIRD_PLACE".equals(f.stage()) ? 1 : 0));
        var resolved = new HashMap<UUID, Tournament.Fixture>();
        for (var f : sorted) {
            UUID a = f.teamAId() != null ? f.teamAId() : team(f.sourceA(), resolved, groupStandings, groupsComplete);
            UUID b = f.teamBId() != null ? f.teamBId() : team(f.sourceB(), resolved, groupStandings, groupsComplete);
            String roundName = "KNOCKOUT".equals(f.stage()) && f.round() != null ? roundName(f.round(), lastRound)
                : "THIRD_PLACE".equals(f.stage()) ? "Third place" : null;
            var next = f.withTeams(a, b, label(f.sourceA(), a, teamNames, groupNames, labels), label(f.sourceB(), b, teamNames, groupNames, labels), roundName);
            resolved.put(f.id(), next);
        }
        return fixtures.stream().map(f -> resolved.get(f.id())).toList();
    }

    private static UUID team(String source, Map<UUID, Tournament.Fixture> resolved, Map<UUID, List<Tournament.Standing>> standings, java.util.Set<UUID> complete) {
        if (source == null) return null;
        if (source.startsWith("T:")) return UUID.fromString(source.substring(2));
        if (source.startsWith("G:")) {
            var parts = source.split(":");
            UUID group = UUID.fromString(parts[1]);
            int rank = Integer.parseInt(parts[2]);
            if (!complete.contains(group)) return null;
            var table = standings.getOrDefault(group, List.of());
            return rank <= table.size() ? table.get(rank - 1).teamId() : null;
        }
        if (source.startsWith("W:") || source.startsWith("L:")) {
            var previous = resolved.get(UUID.fromString(source.substring(2)));
            if (previous == null || !previous.completed() || previous.teamAId() == null || previous.teamBId() == null) return null;
            boolean aWon = "TEAM_A".equals(previous.winner());
            boolean bWon = "TEAM_B".equals(previous.winner());
            if (!aWon && !bWon) return null; // a knockout draw decides nothing: the tie-break must be played
            boolean wantWinner = source.startsWith("W:");
            return wantWinner == aWon ? previous.teamAId() : previous.teamBId();
        }
        return null;
    }

    private static String label(String source, UUID team, Map<UUID, String> teamNames, Map<UUID, String> groupNames, Map<UUID, String> labels) {
        if (team != null) return teamNames.getOrDefault(team, "Team");
        if (source == null) return "To be decided";
        if (source.startsWith("G:")) {
            var parts = source.split(":");
            String group = "Group " + groupNames.getOrDefault(UUID.fromString(parts[1]), "?");
            return switch (parts[2]) { case "1" -> "Winner " + group; case "2" -> "Runner-up " + group; case "3" -> "3rd " + group; default -> parts[2] + "th " + group; };
        }
        if (source.startsWith("W:")) return "Winner " + labels.getOrDefault(UUID.fromString(source.substring(2)), "previous match");
        if (source.startsWith("L:")) return "Loser " + labels.getOrDefault(UUID.fromString(source.substring(2)), "previous match");
        return "To be decided";
    }

    /** The champion once the final has a winner (null otherwise). */
    public static UUID champion(List<Tournament.Fixture> resolved) {
        int lastRound = resolved.stream().filter(f -> "KNOCKOUT".equals(f.stage()) && f.round() != null).mapToInt(Tournament.Fixture::round).max().orElse(0);
        for (var f : resolved)
            if ("KNOCKOUT".equals(f.stage()) && f.round() != null && f.round() == lastRound && f.completed()) {
                if ("TEAM_A".equals(f.winner())) return f.teamAId();
                if ("TEAM_B".equals(f.winner())) return f.teamBId();
            }
        return null;
    }
}
