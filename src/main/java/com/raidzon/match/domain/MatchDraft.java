package com.raidzon.match.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.raidzon.match.domain.MatchState.*;

/** Private working copy; partial mutations never escape a rejected transition. */
final class MatchDraft {
    final List<String> names = new ArrayList<>();
    final List<List<Player>> players = new ArrayList<>();
    final List<List<String>> queues = new ArrayList<>();
    final List<Integer> substitutions = new ArrayList<>();
    final List<Integer> scores, tieScores, pairScores, tieRaids;
    final int firstTurn, halfMinutes, raidSeconds;
    int goldenPair, half, turn, raidNumber;
    Phase phase;
    Status status;
    Winner winner;
    Clock clock, raidClock;
    String currentRaiderId;
    boolean expiryReviewed;
    List<List<String>> tieBreakerRaiders;
    final List<String> lastTieRaiders;
    /** raidzon-v5 only (null otherwise): consecutive empty raids per team this half. */
    List<Integer> emptyRaids;

    MatchDraft(MatchState state) {
        tieBreakerRaiders = state.tieBreakerRaiders();
        lastTieRaiders = new ArrayList<>(state.lastTieRaiders());
        for (var team : state.teams()) {
            names.add(team.name()); players.add(new ArrayList<>(team.players()));
            queues.add(new ArrayList<>(team.queue())); substitutions.add(team.activeSubstitutions());
        }
        scores = new ArrayList<>(state.scores()); tieScores = new ArrayList<>(state.tieScores());
        pairScores = new ArrayList<>(state.pairScores()); tieRaids = new ArrayList<>(state.tieRaids());
        goldenPair = state.goldenPair(); half = state.half(); phase = state.phase(); status = state.status();
        turn = state.turn(); firstTurn = state.firstTurn(); raidNumber = state.raidNumber(); winner = state.winner();
        halfMinutes = state.halfMinutes(); raidSeconds = state.raidSeconds(); clock = state.clock();
        raidClock = state.raidClock(); currentRaiderId = state.currentRaiderId(); expiryReviewed = state.expiryReviewed();
        emptyRaids = state.emptyRaids() == null ? null : new ArrayList<>(state.emptyRaids());
    }
    Player find(int side, String id) { return players.get(side).stream().filter(p -> p.id().equals(id)).findFirst().orElse(null); }
    Player player(int side, String id, PlayerStatus status) {
        Player p = find(side, id);
        require(p != null && p.status() == status, "Player must be " + status.name().toLowerCase(java.util.Locale.ROOT) + ".");
        return p;
    }
    void update(int side, String id, UnaryOperator<Player> change) {
        var roster = players.get(side);
        for (int i = 0; i < roster.size(); i++) if (roster.get(i).id().equals(id)) { roster.set(i, change.apply(roster.get(i))); return; }
        throw new IllegalArgumentException("Player not found.");
    }
    int activeCount(int side) { return (int) players.get(side).stream().filter(p -> p.status() == PlayerStatus.ACTIVE).count(); }
    void out(int side, String id) {
        player(side, id, PlayerStatus.ACTIVE);
        update(side, id, p -> p.withStatus(PlayerStatus.OUT)); queues.get(side).add(id);
    }
    void revive(int side, int count) {
        for (int i = 0; i < count && !queues.get(side).isEmpty(); i++) {
            String id = queues.get(side).removeFirst(); player(side, id, PlayerStatus.OUT);
            update(side, id, p -> p.withStatus(PlayerStatus.ACTIVE));
        }
    }
    void stopClocks(long now) { clock = clock.stopped(now); raidClock = raidClock.stopped(now); }
    MatchState freeze() {
        return new MatchState(List.of(new Team(names.get(0), players.get(0), queues.get(0), substitutions.get(0)),
                new Team(names.get(1), players.get(1), queues.get(1), substitutions.get(1))),
                scores, tieScores, pairScores, tieRaids, goldenPair, half, phase, status, turn, firstTurn,
                raidNumber, winner, halfMinutes, raidSeconds, clock, raidClock, currentRaiderId, expiryReviewed, tieBreakerRaiders, lastTieRaiders,
                emptyRaids);
    }
}
