package com.raidzon.match.domain;

import com.raidzon.scoring.domain.RaidFacts;
import com.raidzon.scoring.domain.RaidScoringEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.raidzon.match.domain.MatchState.*;
import static com.raidzon.match.domain.ScoreComponent.Kind.*;

/** Deterministic raidzon-v3 transitions. The caller supplies trusted prior state and logical time. */
public final class MatchEngine {
    public static final String RULESET_VERSION = "raidzon-v3";
    public static boolean supports(String version) { return "raidzon-v2".equals(version) || RULESET_VERSION.equals(version); }
    private final String rulesetVersion;
    public MatchEngine() { this(RULESET_VERSION); }
    public MatchEngine(String version) {
        require(supports(version), "Unsupported ruleset version.");
        this.rulesetVersion = version;
    }
    private final RaidScoringEngine scoring = new RaidScoringEngine();

    public MatchTransition apply(MatchState previous, MatchAction action, long now) {
        Objects.requireNonNull(previous, "previous"); Objects.requireNonNull(action, "action");
        require(now >= 0, "Invalid event time.");
        require(!(action instanceof MatchAction.Undo), "Undo requires authoritative event history.");
        require(previous.status() != Status.COMPLETED, "This match is complete. Undo the last event to correct it.");
        var state = new MatchDraft(previous);
        var components = new ArrayList<ScoreComponent>();
        String summary = switch (action) {
            case MatchAction.StartRaid start -> startRaid(state, start, now);
            case MatchAction.Raid raid -> raid(state, raid, now, components);
            case MatchAction.Technical technical -> {
                require(state.status == Status.LIVE, "Technical points require a live match.");
                add(components, technical.side(), TECHNICAL, 1, null);
                yield state.names.get(technical.side()) + ": technical point";
            }
            case MatchAction.DefenderSelfOut selfOut -> {
                require(state.status == Status.LIVE, "Self-Out requires a live match.");
                require(state.currentRaiderId == null, "Include defender Self-Out in the current raid result.");
                int defend = 1 - state.turn;
                var subject = state.player(defend, selfOut.playerId(), PlayerStatus.ACTIVE);
                state.out(defend, subject.id());
                add(components, state.turn, SELF_OUT, 1, null);
                state.revive(state.turn, 1); allOut(state, defend, components);
                yield subject.name() + ": defender self-out";
            }
            case MatchAction.Substitute substitute -> substitute(state, substitute);
            case MatchAction.TieBreak tieBreak -> {
                require(state.status == Status.TIED, "Tie-break requires tied regulation scores.");
                require(tieBreak.raiderIds().size() == 2, "Select five distinct playing raiders per team in order.");
                for (int side = 0; side < 2; side++) {
                    var ids = tieBreak.raiderIds().get(side);
                    require(ids.size() == 5 && new HashSet<>(ids).size() == 5, "Select five distinct playing raiders per team in order.");
                    for (var id : ids) require(state.find(side, id) != null && state.find(side, id).status() != PlayerStatus.BENCH, "Select five distinct playing raiders per team in order.");
                }
                state.tieBreakerRaiders = tieBreak.raiderIds(); state.lastTieRaiders.replaceAll(ignored -> "");
                state.status = Status.LIVE; state.phase = Phase.TIE_BREAK; state.turn = state.firstTurn;
                state.tieRaids.replaceAll(ignored -> 0); state.tieScores.replaceAll(ignored -> 0);
                state.clock = new Clock(state.clock.remainingMs(), null); resetCourt(state);
                yield "tie break";
            }
            case MatchAction.Lifecycle lifecycle -> lifecycle(state, lifecycle, now);
            case MatchAction.Undo ignored -> throw new IllegalArgumentException("Undo requires authoritative event history.");
        };
        for (var component : components) {
            if (state.phase == Phase.REGULATION) increment(state.scores, component.side(), component.points());
            else {
                increment(state.tieScores, component.side(), component.points());
                if (state.phase == Phase.GOLDEN_RAID) increment(state.pairScores, component.side(), component.points());
            }
        }
        if (action instanceof MatchAction.Raid && state.phase != Phase.REGULATION) finishPair(state);
        return new MatchTransition(state.freeze(), components, summary);
    }

    private String startRaid(MatchDraft state, MatchAction.StartRaid action, long now) {
        require(state.status == Status.LIVE && state.currentRaiderId == null, "Finish the current raid first.");
        if (state.phase != Phase.REGULATION) {
            var selected = state.tieBreakerRaiders.get(state.turn);
            require(selected.contains(action.raiderId()), "Choose one of the five selected tie-break raiders.");
            require(state.phase != Phase.TIE_BREAK || selected.get(state.tieRaids.get(state.turn)).equals(action.raiderId()), "Follow the selected five-raider sequence.");
            require(state.phase != Phase.GOLDEN_RAID || !state.lastTieRaiders.get(state.turn).equals(action.raiderId()), "A raider cannot take consecutive Golden Raid turns for their team.");
            resetCourt(state);
        }
        state.player(state.turn, action.raiderId(), PlayerStatus.ACTIVE);
        state.currentRaiderId = action.raiderId();
        state.raidClock = new Clock(state.raidSeconds * 1000L, now); state.expiryReviewed = false;
        return "Raid " + state.raidNumber + " started";
    }

    private String raid(MatchDraft state, MatchAction.Raid action, long now, List<ScoreComponent> components) {
        if (state.phase != Phase.REGULATION && action.outcome().equals("EMPTY") && !action.bonus())
            action = new MatchAction.Raid(action.raiderId(), "SELF_OUT", action.defenderIds(), action.selfOutDefenderIds(), action.tacklerId(), false, action.defenderOutOrder());
        require(state.status == Status.LIVE, "Resume the match before scoring.");
        require(action.raiderId().equals(state.currentRaiderId), "Start the raid with this raider first.");
        require(state.raidClock.remaining(now) > 0 || state.expiryReviewed || action.outcome().equals("SELF_OUT"),
                "Resolve the raid expiry before scoring.");
        int attack = state.turn, defend = 1 - attack;
        var raider = state.player(attack, action.raiderId(), PlayerStatus.ACTIVE);
        var selected = new ArrayList<>(action.defenderIds()); selected.addAll(action.selfOutDefenderIds());
        require(new HashSet<>(selected).size() == selected.size(), "A defender can only be selected once.");
        var outs = action.defenderOutOrder() == null ? selected : action.defenderOutOrder();
        require(outs.size() == selected.size() && new HashSet<>(outs).size() == selected.size() && outs.containsAll(selected), "OUT order must contain every selected defender exactly once.");
        require(action.defenderIds().isEmpty() || action.selfOutDefenderIds().isEmpty() || action.defenderOutOrder() != null, "Record the sequential OUT order for mixed outs.");
        outs.forEach(id -> state.player(defend, id, PlayerStatus.ACTIVE));
        var result = scoring.evaluate(new RaidFacts(action.outcome(), state.activeCount(defend),
                action.defenderIds().size(), action.bonus(), action.selfOutDefenderIds().size()));
        if (action.outcome().equals("TACKLE")) {
            require(action.tacklerId() != null && !action.tacklerId().isBlank(), "Choose the defender credited with the tackle.");
            state.player(defend, action.tacklerId(), PlayerStatus.ACTIVE);
            state.update(defend, action.tacklerId(), p -> p.withPoints(0, result.defenderPoints()));
        } else require(action.tacklerId() == null || action.tacklerId().isEmpty(), "Only a tackle can name a tackler.");
        state.update(attack, raider.id(), p -> p.withPoints(result.raiderPoints(), 0));
        add(components, attack, TOUCH, action.defenderIds().size(), raider.id());
        add(components, attack, BONUS, action.bonus() ? 1 : 0, raider.id());
        add(components, attack, SELF_OUT, action.selfOutDefenderIds().size(), null);
        add(components, defend, TACKLE, result.defenderPoints(), action.tacklerId());
        add(components, defend, SUPER_TACKLE_EXTRA, result.superTackleExtra(), null);
        if (action.outcome().equals("SELF_OUT")) add(components, defend, SELF_OUT, 1, null);
        outs.forEach(id -> state.out(defend, id));
        if (action.outcome().equals("TACKLE") || action.outcome().equals("SELF_OUT")) state.out(attack, raider.id());
        // A defender self-out cannot rescue a team whose last raider was tackled.
        boolean lastRaiderTackled = rulesetVersion.equals(RULESET_VERSION) && action.outcome().equals("TACKLE")
                && !action.selfOutDefenderIds().isEmpty() && state.activeCount(attack) == 0;
        if (!lastRaiderTackled) state.revive(attack, result.attackingRevivals());
        state.revive(defend, result.defendingRevivals());
        allOut(state, attack, components); allOut(state, defend, components);
        String summary = raider.name() + ": " + action.outcome().toLowerCase(Locale.ROOT).replace('_', ' ')
                + (action.bonus() ? " + bonus" : "") + (result.raiderPoints() >= 3 ? " · Super Raid" : "");
        if (!action.selfOutDefenderIds().isEmpty()) summary += " · " + action.selfOutDefenderIds().size() + " defender self-out";
        if (state.phase != Phase.REGULATION) {
            increment(state.tieRaids, attack, 1); state.lastTieRaiders.set(attack, raider.id());
        }
        state.raidNumber++; state.turn = defend; state.currentRaiderId = null;
        state.raidClock = new Clock(state.raidSeconds * 1000L, null); state.expiryReviewed = false;
        return summary;
    }

    private String substitute(MatchDraft state, MatchAction.Substitute action) {
        require((state.status == Status.LIVE || state.status == Status.PAUSED || state.status == Status.HALF_TIME)
                && state.phase == Phase.REGULATION, "Substitutions are available during regulation.");
        require(state.currentRaiderId == null, "Finish the raid before substituting.");
        var incoming = state.player(action.side(), action.incomingId(), PlayerStatus.BENCH);
        var outgoing = state.find(action.side(), action.outgoingId());
        require(outgoing != null && outgoing.status() != PlayerStatus.BENCH, "Choose an on-court or OUT player.");
        if (outgoing.status() == PlayerStatus.ACTIVE) {
            require(state.substitutions.get(action.side()) < 3, "Three active substitutions already used this half.");
            increment(state.substitutions, action.side(), 1);
        } else state.queues.get(action.side()).replaceAll(id -> id.equals(outgoing.id()) ? incoming.id() : id);
        state.update(action.side(), incoming.id(), p -> p.withStatus(outgoing.status()));
        state.update(action.side(), outgoing.id(), p -> p.withStatus(PlayerStatus.BENCH));
        return incoming.name() + " replaces " + outgoing.name();
    }

    private String lifecycle(MatchDraft state, MatchAction.Lifecycle action, long now) {
        switch (action) {
            case NOT_EXPIRED -> {
                require(state.status == Status.LIVE && state.currentRaiderId != null
                        && state.raidClock.remaining(now) == 0 && !state.expiryReviewed,
                        "No raid expiry decision is pending.");
                state.expiryReviewed = true;
                return "Official decision: raid not expired";
            }
            case PAUSE -> {
                require(state.status == Status.LIVE, "Only a live match can be paused.");
                state.stopClocks(now); state.status = Status.PAUSED;
            }
            case RESUME -> {
                require(state.status == Status.PAUSED, "The match is not paused.");
                state.status = Status.LIVE;
                state.clock = new Clock(state.clock.remainingMs(), state.phase == Phase.REGULATION ? now : null);
                if (state.currentRaiderId != null) state.raidClock = new Clock(state.raidClock.remainingMs(), now);
            }
            case END_HALF -> {
                require(state.currentRaiderId == null, "Finish the raid before ending the half.");
                require(state.half == 1 && state.phase == Phase.REGULATION && liveOrPaused(state), "Only the first half can end here.");
                state.stopClocks(now); state.status = Status.HALF_TIME;
            }
            case SECOND_HALF -> {
                require(state.status == Status.HALF_TIME, "End the first half first.");
                state.half = 2; state.turn = 1 - state.firstTurn; state.status = Status.LIVE;
                state.substitutions.replaceAll(ignored -> 0);
                state.clock = new Clock(state.halfMinutes * 60_000L, now);
            }
            case END_MATCH -> {
                require(state.currentRaiderId == null, "Finish the raid before ending the match.");
                require(state.half == 2 && state.phase == Phase.REGULATION && liveOrPaused(state), "End regulation during the second half.");
                state.stopClocks(now);
                state.status = state.scores.get(0).equals(state.scores.get(1)) ? Status.TIED : Status.COMPLETED;
                if (state.status == Status.COMPLETED) state.winner = winner(state.scores);
            }
            case DRAW -> {
                require(state.status == Status.TIED, "A draw requires tied regulation scores.");
                state.status = Status.COMPLETED; state.winner = Winner.DRAW;
            }
        }
        return action.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void allOut(MatchDraft state, int side, List<ScoreComponent> components) {
        if (state.activeCount(side) > 0) return;
        add(components, 1 - side, ALL_OUT, 2, null);
        state.players.get(side).replaceAll(p -> p.status() == PlayerStatus.OUT ? p.withStatus(PlayerStatus.ACTIVE) : p);
        state.queues.get(side).clear();
    }
    private void finishPair(MatchDraft state) {
        int limit = state.phase == Phase.TIE_BREAK ? 5 : 1;
        if (state.tieRaids.get(0) != limit || state.tieRaids.get(1) != limit) return;
        var points = state.phase == Phase.TIE_BREAK ? state.tieScores : state.pairScores;
        if (!points.get(0).equals(points.get(1))) { state.status = Status.COMPLETED; state.winner = winner(points); }
        else {
            state.phase = Phase.GOLDEN_RAID; state.goldenPair++;
            state.tieRaids.replaceAll(ignored -> 0); state.pairScores.replaceAll(ignored -> 0);
            resetCourt(state);
        }
    }
    private void resetCourt(MatchDraft state) {
        for (int side = 0; side < 2; side++) {
            state.players.get(side).replaceAll(p -> p.status() == PlayerStatus.OUT ? p.withStatus(PlayerStatus.ACTIVE) : p);
            state.queues.get(side).clear();
        }
    }
    private static Winner winner(List<Integer> scores) { return scores.get(0) > scores.get(1) ? Winner.TEAM_A : Winner.TEAM_B; }
    private static boolean liveOrPaused(MatchDraft state) { return state.status == Status.LIVE || state.status == Status.PAUSED; }
    private static void increment(List<Integer> values, int side, int amount) { values.set(side, Math.addExact(values.get(side), amount)); }
    private static void add(List<ScoreComponent> components, int side, ScoreComponent.Kind kind, int points, String playerId) {
        if (points > 0) components.add(new ScoreComponent(kind, side, points, playerId));
    }
}
