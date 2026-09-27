package com.raidzon.match.domain;

import java.util.List;
import java.util.Objects;

/** Recorded facts only. Callers cannot supply totals, revivals or a next snapshot. */
public sealed interface MatchAction {
    record StartRaid(String raiderId) implements MatchAction {
        public StartRaid { required(raiderId); }
    }
    record Raid(String raiderId, String outcome, List<String> defenderIds,
                List<String> selfOutDefenderIds, String tacklerId, boolean bonus, List<String> defenderOutOrder) implements MatchAction {
        public Raid(String raiderId, String outcome, List<String> defenderIds, List<String> selfOutDefenderIds, String tacklerId, boolean bonus) {
            this(raiderId, outcome, defenderIds, selfOutDefenderIds, tacklerId, bonus, null);
        }
        public Raid {
            required(raiderId); required(outcome);
            defenderIds = List.copyOf(Objects.requireNonNull(defenderIds, "defenderIds"));
            selfOutDefenderIds = selfOutDefenderIds == null ? List.of() : List.copyOf(selfOutDefenderIds);
            defenderOutOrder = defenderOutOrder == null ? null : List.copyOf(defenderOutOrder);
        }
    }
    record Technical(int side) implements MatchAction { public Technical { MatchAction.side(side); } }
    record DefenderSelfOut(String playerId) implements MatchAction { public DefenderSelfOut { required(playerId); } }
    record Substitute(int side, String outgoingId, String incomingId) implements MatchAction {
        public Substitute { MatchAction.side(side); required(outgoingId); required(incomingId); }
    }
    record TieBreak(List<List<String>> raiderIds) implements MatchAction {
        public TieBreak { raiderIds = raiderIds == null ? List.of() : raiderIds.stream().map(List::copyOf).toList(); }
    }
    enum Lifecycle implements MatchAction { NOT_EXPIRED, PAUSE, RESUME, END_HALF, SECOND_HALF, END_MATCH, DRAW }
    record Undo(String targetEventId) implements MatchAction { public Undo { required(targetEventId); } }

    private static void required(String value) { MatchState.require(value != null && !value.isBlank(), "Action identifier is required."); }
    private static void side(int value) { MatchState.require(value == 0 || value == 1, "Choose a team."); }
}
