package com.raidzon.tournament.dto;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Tournament(UUID id, String name, String venue, LocalDate startsOn, int halfMinutes, int raidSeconds) {
    public record TeamInput(UUID id, String name) {}
    public record RosterPlayer(String name, String phone) {}
    public record RosterInput(List<RosterPlayer> players, int expectedRevision) {}
    /** teamId links to a saved (reusable) team when the roster was registered from one; otherwise null. */
    public record Team(UUID id, String name, int rosterRevision, List<RosterPlayer> roster, UUID teamId) {
        public Team(UUID id, String name, int rosterRevision, List<RosterPlayer> roster) { this(id, name, rosterRevision, roster, null); }
    }
    /** Registers a saved team: id is the new tournament team's ID (client-generated, retry-safe). */
    public record SavedTeamInput(UUID id, UUID teamId) {}
    public static final int MIN_ROSTER = 7;
    public static final int MAX_ROSTER = 20;
    public record FixtureInput(UUID id, UUID teamAId, UUID teamBId, Instant scheduledAt) {}
    public record LinkInput(UUID matchId) {}
    public record ScheduleInput(Instant scheduledAt, int expectedRevision) {}
    public record Fixture(UUID id, UUID teamAId, UUID teamBId, Instant scheduledAt, UUID matchId,
                          String status, Integer scoreA, Integer scoreB, String phase,
                          Integer tieScoreA, Integer tieScoreB, String winner, int scheduleRevision) {}
    public record Standing(UUID teamId, String teamName, int rank, int played, int won, int drawn, int lost,
                           int tablePoints, int pointsFor, int pointsAgainst, int scoreDifference) {}
    public record Detail(Tournament tournament, List<Team> teams, List<Fixture> fixtures, List<Standing> standings, boolean registrationOpen) {}
    public record PublicTeam(UUID id, String name, List<String> players) {}
    public record PublicDetail(Tournament tournament, List<PublicTeam> teams, List<Fixture> fixtures, List<Standing> standings, boolean registrationOpen) {}
    public record PlayerRanking(UUID playerId,String name,long matches,long raidPoints,long tacklePoints) {}
}
