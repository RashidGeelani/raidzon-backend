package com.raidzon.match.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable gameplay snapshot. No persistence, transport or system-clock dependency. */
public record MatchState(
        List<Team> teams, List<Integer> scores, List<Integer> tieScores,
        List<Integer> pairScores, List<Integer> tieRaids, int goldenPair, int half,
        Phase phase, Status status, int turn, int firstTurn, int raidNumber, Winner winner,
        int halfMinutes, int raidSeconds, Clock clock, Clock raidClock,
        String currentRaiderId, boolean expiryReviewed, List<List<String>> tieBreakerRaiders, List<String> lastTieRaiders
) {
    public enum Phase { REGULATION, TIE_BREAK, GOLDEN_RAID }
    public enum Status { LIVE, PAUSED, HALF_TIME, TIED, COMPLETED }
    public enum PlayerStatus { ACTIVE, OUT, BENCH }
    public enum Winner { TEAM_A, TEAM_B, DRAW }

    public record Player(String id, String name, String phone, PlayerStatus status,
                         int raidPoints, int tacklePoints) {
        public Player {
            require(id != null && !id.isBlank() && name != null && !name.isBlank(), "Player identity is required.");
            phone = phone == null ? "" : phone; // optional: players without a phone are match-only
            require(status != null && raidPoints >= 0 && tacklePoints >= 0, "Invalid player state.");
        }
        Player withStatus(PlayerStatus next) { return new Player(id, name, phone, next, raidPoints, tacklePoints); }
        Player withPoints(int raid, int tackle) { return new Player(id, name, phone, status, Math.addExact(raidPoints, raid), Math.addExact(tacklePoints, tackle)); }
        public boolean superTen() { return raidPoints >= 10; }
        public boolean highFive() { return tacklePoints >= 5; }
    }

    public record Team(String name, List<Player> players, List<String> queue, int activeSubstitutions) {
        public Team {
            require(name != null && !name.isBlank(), "Team name is required.");
            players = List.copyOf(Objects.requireNonNull(players, "players"));
            queue = List.copyOf(Objects.requireNonNull(queue, "queue"));
            require(players.size() >= 7 && players.size() <= 12, "A roster needs seven starters and up to five substitutes.");
            require(activeSubstitutions >= 0 && activeSubstitutions <= 3, "Invalid substitution count.");
            require(players.stream().filter(p -> p.status() != PlayerStatus.BENCH).count() == 7,
                    "A team must retain seven playing positions.");
            var outs = players.stream().filter(p -> p.status() == PlayerStatus.OUT).map(Player::id).sorted().toList();
            require(outs.equals(queue.stream().sorted().toList()), "OUT players and FIFO queue differ.");
            require(new HashSet<>(queue).size() == queue.size(), "Duplicate player in queue.");
        }
        public long activeCount() { return players.stream().filter(p -> p.status() == PlayerStatus.ACTIVE).count(); }
    }

    public record Clock(long remainingMs, Long startedAt) {
        public Clock { require(remainingMs >= 0 && (startedAt == null || startedAt >= 0), "Invalid clock state."); }
        public long remaining(long now) { return Math.max(0, remainingMs - (startedAt == null ? 0 : Math.max(0, now - startedAt))); }
        public Clock settled(long now) { return new Clock(remaining(now), startedAt == null ? null : now); }
        public Clock stopped(long now) { return new Clock(remaining(now), null); }
        public Clock restored(long now) { return new Clock(remainingMs, startedAt == null ? null : now); }
    }

    public MatchState {
        teams = List.copyOf(Objects.requireNonNull(teams, "teams"));
        tieBreakerRaiders = Objects.requireNonNull(tieBreakerRaiders).stream().map(List::copyOf).toList();
        lastTieRaiders = List.copyOf(Objects.requireNonNull(lastTieRaiders));
        require(tieBreakerRaiders.size() == 2 && lastTieRaiders.size() == 2, "Tie-break selections need two teams.");
        require(teams.size() == 2, "A match needs two teams.");
        require(!teams.get(0).name().equalsIgnoreCase(teams.get(1).name()), "Enter two different team names.");
        var ids = new HashSet<String>();
        var phones = new HashSet<String>();
        for (var team : teams) for (var player : team.players()) {
            require(ids.add(player.id()), "A player cannot occupy two roster positions.");
            require(player.phone().isBlank() || phones.add(player.phone()), "Each player must have a unique phone number across both teams.");
        }
        scores = pair(scores); tieScores = pair(tieScores); pairScores = pair(pairScores); tieRaids = pair(tieRaids);
        require(phase != null && status != null, "Match phase and status are required.");
        require((turn == 0 || turn == 1) && (firstTurn == 0 || firstTurn == 1), "Invalid raiding side.");
        require((half == 1 || half == 2) && raidNumber >= 1 && goldenPair >= 0, "Invalid match counters.");
        require(halfMinutes >= 1 && halfMinutes <= 60 && raidSeconds >= 5 && raidSeconds <= 120, "Invalid match duration.");
        Objects.requireNonNull(clock, "clock"); Objects.requireNonNull(raidClock, "raidClock");
        require((status == Status.COMPLETED) == (winner != null), "Winner must agree with completion state.");
        require(status != Status.TIED || (phase == Phase.REGULATION && scores.get(0).equals(scores.get(1))), "Tie resolution requires a regulation draw.");
        require(phase == Phase.REGULATION || half == 2, "Tie phases follow the second half.");
        require(phase != Phase.GOLDEN_RAID || goldenPair >= 1, "Golden Raid needs a pair number.");
        if (currentRaiderId != null) {
            String raiderId = currentRaiderId;
            require(status == Status.LIVE || status == Status.PAUSED, "An active raid requires live or paused status.");
            require(teams.get(turn).players().stream().anyMatch(p -> p.id().equals(raiderId) && p.status() == PlayerStatus.ACTIVE), "Current raider must be active on the raiding side.");
        } else require(raidClock.startedAt() == null && !expiryReviewed, "No raid can have a running raid clock or expiry review.");
    }

    public static MatchState start(List<Team> teams, int firstTurn, int halfMinutes, int raidSeconds, long now) {
        require(now >= 0, "Invalid event time.");
        for (var team : teams) {
            require(team.activeCount() == 7 && team.queue().isEmpty() && team.activeSubstitutions() == 0,
                    "A new match requires seven active starters and an empty OUT queue.");
            require(team.players().stream().allMatch(p -> p.raidPoints() == 0 && p.tacklePoints() == 0), "A new match starts with zero player statistics.");
        }
        return new MatchState(teams, List.of(0, 0), List.of(0, 0), List.of(0, 0), List.of(0, 0), 0, 1,
                Phase.REGULATION, Status.LIVE, firstTurn, firstTurn, 1, null, halfMinutes, raidSeconds,
                new Clock(halfMinutes * 60_000L, now), new Clock(raidSeconds * 1_000L, null), null, false, List.of(List.of(), List.of()), List.of("", ""));
    }

    public MatchState settleClocks(long now) { return withClocks(clock.settled(now), raidClock.settled(now)); }
    /** v4 setup: the match clock waits for the first raid. */
    public MatchState awaitingFirstRaid() { return withClocks(new Clock(clock.remainingMs(), null), raidClock); }
    public MatchState restoreClocks(long now) { return withClocks(clock.restored(now), raidClock.restored(now)); }
    private MatchState withClocks(Clock matchClock, Clock nextRaidClock) {
        return new MatchState(teams, scores, tieScores, pairScores, tieRaids, goldenPair, half, phase, status,
                turn, firstTurn, raidNumber, winner, halfMinutes, raidSeconds, matchClock, nextRaidClock, currentRaiderId, expiryReviewed, tieBreakerRaiders, lastTieRaiders);
    }
    private static List<Integer> pair(List<Integer> values) {
        values = List.copyOf(Objects.requireNonNull(values, "score/counter pair"));
        require(values.size() == 2 && values.stream().allMatch(n -> n >= 0), "Scores and counters need two non-negative values.");
        return values;
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
