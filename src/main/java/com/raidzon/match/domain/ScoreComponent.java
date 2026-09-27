package com.raidzon.match.domain;

public record ScoreComponent(Kind kind, int side, int points, String playerId) {
    public enum Kind { TOUCH, BONUS, TACKLE, SUPER_TACKLE_EXTRA, SELF_OUT, ALL_OUT, TECHNICAL }
    public ScoreComponent {
        MatchState.require(kind != null && (side == 0 || side == 1) && points > 0, "Invalid score component.");
        boolean individual = kind == Kind.TOUCH || kind == Kind.BONUS || kind == Kind.TACKLE;
        MatchState.require(individual == (playerId != null && !playerId.isBlank()), "Individual credit must agree with component kind.");
    }
}
