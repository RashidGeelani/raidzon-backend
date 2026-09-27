package com.raidzon.match.dto;

import com.raidzon.match.domain.MatchState;
import java.util.UUID;

public record MatchRegistration(UUID matchId, String rulesetVersion, int version, boolean duplicate, MatchState state) {}
