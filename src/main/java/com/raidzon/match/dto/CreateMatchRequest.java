package com.raidzon.match.dto;

import com.raidzon.match.domain.MatchState;
import java.util.List;
import java.util.UUID;

/** Immutable roster snapshot input; account/device authority is supplied separately by a trusted caller. */
public record CreateMatchRequest(UUID matchId, List<TeamRoster> teams, int firstTurn,
                                 int halfMinutes, int raidSeconds, long startedAt, String rulesetVersion) {
    public CreateMatchRequest(UUID matchId, List<TeamRoster> teams, int firstTurn, int halfMinutes, int raidSeconds, long startedAt) {
        this(matchId, teams, firstTurn, halfMinutes, raidSeconds, startedAt, null);
    }
    public String resolvedRuleset() {
        String version = rulesetVersion == null ? com.raidzon.match.domain.MatchEngine.RULESET_VERSION : rulesetVersion;
        if (!com.raidzon.match.domain.MatchEngine.supports(version)) throw new IllegalArgumentException("Unsupported ruleset version.");
        return version;
    }
    public CreateMatchRequest { teams = teams == null ? List.of() : List.copyOf(teams); }
    public record Player(UUID id, String name, String phone) {}
    public record TeamRoster(String name, List<Player> players) {
        public TeamRoster { players = players == null ? List.of() : List.copyOf(players); }
    }

    public MatchState initialState() {
        if (matchId == null || teams.size() != 2) throw new IllegalArgumentException("A match ID and two teams are required.");
        if (startedAt < 0 || startedAt > 9007199254740991L) throw new IllegalArgumentException("Match time must be a safe non-negative millisecond value.");
        var rosters = teams.stream().map(team -> {
            var players = java.util.stream.IntStream.range(0, team.players().size()).mapToObj(index -> {
                var p = team.players().get(index);
                if (p.id() == null || p.phone() == null || !p.phone().matches("\\+[1-9][0-9]{7,14}"))
                    throw new IllegalArgumentException("Player IDs and canonical international phone numbers are required.");
                return new MatchState.Player(p.id().toString(), p.name(), p.phone(),
                        index < 7 ? MatchState.PlayerStatus.ACTIVE : MatchState.PlayerStatus.BENCH, 0, 0);
            }).toList();
            return new MatchState.Team(team.name(), players, List.of(), 0);
        }).toList();
        var state = MatchState.start(rosters, firstTurn, halfMinutes, raidSeconds, startedAt);
        return com.raidzon.match.domain.MatchEngine.clockStartsWithFirstRaid(resolvedRuleset()) ? state.awaitingFirstRaid() : state;
    }
}
