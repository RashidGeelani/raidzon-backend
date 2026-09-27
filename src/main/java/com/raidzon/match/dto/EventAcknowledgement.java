package com.raidzon.match.dto;

import com.raidzon.match.domain.MatchState;
import java.util.UUID;

/** acceptedVersion identifies this event; currentVersion may be newer on a delayed retry. */
public record EventAcknowledgement(UUID eventId, int acceptedVersion, int currentVersion,
                                   boolean duplicate, MatchState state) {}
