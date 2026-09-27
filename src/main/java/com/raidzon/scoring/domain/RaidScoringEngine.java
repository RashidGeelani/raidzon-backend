package com.raidzon.scoring.domain;

import java.util.Set;

/** Pure scoring components. MatchEngine validates roster, player state and lifecycle before applying them. */
public final class RaidScoringEngine {
    private static final Set<String> OUTCOMES = Set.of("TOUCH", "EMPTY", "TACKLE", "SELF_OUT");

    public RaidScore evaluate(RaidFacts facts) {
        if (facts == null || facts.outcome() == null || !OUTCOMES.contains(facts.outcome())) {
            throw new IllegalArgumentException("Invalid raid outcome.");
        }
        int defenders = facts.defendingCount();
        int touches = facts.touches();
        if (defenders < 1 || defenders > 7) throw new IllegalArgumentException("Invalid defending count.");
        if (touches < 0 || touches > defenders) throw new IllegalArgumentException("Invalid touch count.");
        if ((facts.outcome().equals("TOUCH") && touches == 0) || (facts.outcome().equals("EMPTY") && touches > 0)) {
            throw new IllegalArgumentException("Touch outcome requires defenders out.");
        }
        int selfOuts = facts.defenderSelfOuts();
        if (selfOuts < 0 || touches + selfOuts > defenders) throw new IllegalArgumentException("Invalid defender self-out count.");
        if (facts.bonus() && defenders < 6) throw new IllegalArgumentException("Bonus requires at least six defenders.");

        boolean opposingOuts = (facts.outcome().equals("TACKLE") || facts.outcome().equals("SELF_OUT")) && touches + selfOuts > 0;
        int allOut = touches + selfOuts == defenders && !opposingOuts ? 2 : 0;
        int superTackleExtra = facts.outcome().equals("TACKLE") && defenders <= 3 && !opposingOuts ? 1 : 0;
        int raiderPoints = touches + (facts.bonus() ? 1 : 0);
        int defendingPoints = switch (facts.outcome()) {
            case "TACKLE" -> 1 + superTackleExtra;
            case "SELF_OUT" -> 1;
            default -> 0;
        };
        return new RaidScore(raiderPoints + selfOuts + allOut, defendingPoints, raiderPoints,
                facts.outcome().equals("TACKLE") ? 1 : 0, touches + selfOuts,
                defendingPoints > 0 ? 1 : 0, allOut, superTackleExtra);
    }
}
