package com.raidzon.tournament.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A saved team's request to join a tournament. Player phones are never included. */
public record JoinRequest(UUID id, UUID tournamentId, String tournamentName, UUID teamId, String teamName, String teamCity,
                          int squadSize, List<String> players, String requestedByName, String message, String status,
                          String decisionNote, Instant createdAt, Instant decidedAt, UUID tournamentTeamId) {
    public record CreateInput(UUID id, UUID teamId, String message) {}
    public record Decision(String note) {}
    public record RegistrationInput(boolean open) {}
}
