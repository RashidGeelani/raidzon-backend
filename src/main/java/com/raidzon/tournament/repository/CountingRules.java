package com.raidzon.tournament.repository;

/**
 * Shared SQL rules for what counts in player stats and what appears in public lists, so the profile, the public
 * player page, leaderboards, player search, Explore and the sitemap all agree.
 */
public final class CountingRules {
    private CountingRules() {}

    /** A tournament feeds player stats once it has this many teams and completed matches. */
    public static final int MIN_TEAMS = 4, MIN_COMPLETED = 3;

    /** Names starting with "TEST " mark automated test data: hidden from public lists and never counted in stats. */
    public static String testTournament(String t) { return "(" + t + ".name ILIKE 'TEST %')"; }
    public static String testMatch(String m) {
        return "(" + m + ".projection #>> '{teams,0,name}' ILIKE 'TEST %' OR " + m + ".projection #>> '{teams,1,name}' ILIKE 'TEST %')";
    }

    /** True when tournament t has enough teams and completed (not practice, not removed) matches to count. */
    public static String eligibleTournament(String t) {
        return "(NOT " + testTournament(t) + " AND (SELECT count(*) FROM tournament_teams et WHERE et.tournament_id=" + t + ".id)>=" + MIN_TEAMS
            + " AND (SELECT count(*) FROM tournament_fixtures ef JOIN matches em ON em.id=ef.match_id WHERE ef.tournament_id=" + t + ".id"
            + " AND em.projection->>'status'='COMPLETED' AND NOT em.practice AND em.removed_at IS NULL)>=" + MIN_COMPLETED + ")";
    }

    /**
     * True when match m counts towards player stats: a fixture of an eligible tournament. Friendly matches never count.
     * (Completed, practice, removed and self-scored checks are in AccountRepository.COUNTED.)
     */
    public static String inEligibleTournament(String m) {
        return "EXISTS(SELECT 1 FROM tournament_fixtures sf JOIN tournaments st ON st.id=sf.tournament_id WHERE sf.match_id=" + m + ".id AND "
            + eligibleTournament("st") + ")";
    }
}
