package com.raidzon.tournament.dto;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Tournament(UUID id, String name, String venue, LocalDate startsOn, int halfMinutes, int raidSeconds) {
    public record TeamInput(UUID id, String name) {}
    /** jersey: shirt number 0-999 (unique within the team), optional; it pre-fills match setup. */
    public record RosterPlayer(String name, String phone, Integer jersey) {
        public RosterPlayer(String name, String phone) { this(name, phone, null); }
    }
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
    /**
     * stage: LEAGUE, GROUP, KNOCKOUT or THIRD_PLACE. Knockout sides may be undecided (team id null):
     * sourceA/B say where the team comes from and labelA/B say it in words ("Winner Group A").
     * roundName is set for knockout rounds ("Quarter-final", "Semi-final", "Final").
     */
    public record Fixture(UUID id, UUID teamAId, UUID teamBId, Instant scheduledAt, UUID matchId,
                          String status, Integer scoreA, Integer scoreB, String phase,
                          Integer tieScoreA, Integer tieScoreB, String winner, int scheduleRevision,
                          String stage, UUID groupId, Integer round, Integer slot,
                          String sourceA, String sourceB, String labelA, String labelB, String roundName) {
        public Fixture(UUID id, UUID teamAId, UUID teamBId, Instant scheduledAt, UUID matchId,
                       String status, Integer scoreA, Integer scoreB, String phase,
                       Integer tieScoreA, Integer tieScoreB, String winner, int scheduleRevision) {
            this(id, teamAId, teamBId, scheduledAt, matchId, status, scoreA, scoreB, phase, tieScoreA, tieScoreB, winner, scheduleRevision,
                "LEAGUE", null, null, null, null, null, null, null, null);
        }
        public boolean knockout() { return "KNOCKOUT".equals(stage) || "THIRD_PLACE".equals(stage); }
        public boolean completed() { return "COMPLETED".equals(status) && winner != null; }
        public Fixture withTeams(UUID a, UUID b, String labelA, String labelB, String roundName) {
            return new Fixture(id, a, b, scheduledAt, matchId, status, scoreA, scoreB, phase, tieScoreA, tieScoreB, winner, scheduleRevision,
                stage, groupId, round, slot, sourceA, sourceB, labelA, labelB, roundName);
        }
    }
    public record Standing(UUID teamId, String teamName, int rank, int played, int won, int drawn, int lost,
                           int tablePoints, int pointsFor, int pointsAgainst, int scoreDifference, UUID groupId, boolean qualifies) {
        public Standing(UUID teamId, String teamName, int rank, int played, int won, int drawn, int lost,
                        int tablePoints, int pointsFor, int pointsAgainst, int scoreDifference) {
            this(teamId, teamName, rank, played, won, drawn, lost, tablePoints, pointsFor, pointsAgainst, scoreDifference, null, false);
        }
    }
    public static final List<String> FORMATS = List.of("LEAGUE", "KNOCKOUT", "GROUPS_KNOCKOUT");
    /** locked: a match has been played in a fixture, so format, groups and fixtures can no longer be regenerated. */
    public record Format(String type, int groupCount, int advancePerGroup, boolean thirdPlace, boolean locked, boolean generated) {}
    public record FormatInput(String type, int groupCount, int advancePerGroup, boolean thirdPlace) {}
    /** teamIds in draw order (seed). */
    public record Group(UUID id, String name, List<UUID> teamIds) {}
    /** groups: every team exactly once; for league and knockout send one entry with id null holding the draw order. */
    public record ArrangementInput(List<Group> groups) {}
    /** champion: the final's winner once decided. */
    public record Detail(Tournament tournament, List<Team> teams, List<Fixture> fixtures, List<Standing> standings, boolean registrationOpen,
                         Format format, List<Group> groups, UUID championId) {}
    public record PublicTeam(UUID id, String name, List<String> players) {}
    public record PublicDetail(Tournament tournament, List<PublicTeam> teams, List<Fixture> fixtures, List<Standing> standings, boolean registrationOpen,
                               Format format, List<Group> groups, UUID championId) {}
    public record PlayerRanking(UUID playerId,String name,long matches,long raidPoints,long tacklePoints,String teamName) {}
}
