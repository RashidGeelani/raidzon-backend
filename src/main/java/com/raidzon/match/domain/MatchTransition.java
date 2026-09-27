package com.raidzon.match.domain;

import java.util.List;

public record MatchTransition(MatchState state, List<ScoreComponent> components, String summary) {
    public MatchTransition { components = List.copyOf(components); }
}
