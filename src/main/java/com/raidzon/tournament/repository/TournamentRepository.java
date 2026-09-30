package com.raidzon.tournament.repository;

import com.raidzon.tournament.dto.Tournament;
import com.raidzon.identity.service.AuthFailure;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;
import java.sql.Timestamp;

@Repository @Profile("postgres")
public class TournamentRepository {
    private final JdbcTemplate jdbc;
    public TournamentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final org.springframework.jdbc.core.RowMapper<Tournament> ROW = (r,i) -> new Tournament(
        r.getObject("id",UUID.class),r.getString("name"),r.getString("venue"),r.getDate("starts_on").toLocalDate(),r.getInt("half_minutes"),r.getInt("raid_seconds"));
    public List<Tournament> list(UUID owner) {
        return jdbc.query("SELECT * FROM tournaments WHERE owner_account_id=? ORDER BY created_at DESC,id",ROW,owner);
    }
    public List<Tournament> browse(String search) {
        return jdbc.query("SELECT * FROM tournaments WHERE position(lower(?) in lower(name || ' ' || venue))>0 ORDER BY starts_on DESC,id LIMIT 100",ROW,search);
    }
    public List<Tournament.PlayerRanking> leaderboard(UUID tournamentId,String category){
        String order=switch(category){case "raid"->"raid_points";case "tackle"->"tackle_points";default->"total_points";};
        return jdbc.query("""
            SELECT p.id,p.initial_name,count(DISTINCT m.id) AS played,
                COALESCE(sum((player.value->>'raidPoints')::bigint),0) AS raid_points,
                COALESCE(sum((player.value->>'tacklePoints')::bigint),0) AS tackle_points,
                COALESCE(sum((player.value->>'raidPoints')::bigint + (player.value->>'tacklePoints')::bigint),0) AS total_points
            FROM match_player_links l JOIN player_profiles p ON p.id=l.profile_id JOIN matches m ON m.id=l.match_id
            CROSS JOIN LATERAL jsonb_array_elements(m.projection->'teams') AS team(value)
            CROSS JOIN LATERAL jsonb_array_elements(team.value->'players') AS player(value)
            WHERE player.value->>'id'=l.local_player_id::text AND m.projection->>'status'='COMPLETED'
                AND EXISTS(SELECT 1 FROM tournament_fixtures f WHERE f.match_id=m.id AND (?::uuid IS NULL OR f.tournament_id=?))
            GROUP BY p.id,p.initial_name ORDER BY
            """+order+" DESC,p.initial_name,p.id LIMIT 100",
            (r,i)->new Tournament.PlayerRanking(r.getObject("id",UUID.class),r.getString("initial_name"),r.getLong("played"),r.getLong("raid_points"),r.getLong("tackle_points")),tournamentId,tournamentId);
    }
    public Tournament.PublicDetail publicDetail(UUID id) {
        var owners=jdbc.queryForList("SELECT owner_account_id FROM tournaments WHERE id=?",UUID.class,id);
        if(owners.isEmpty()) throw new AuthFailure(404,"TOURNAMENT_NOT_FOUND","Tournament not found.");
        var detail=detail(id,owners.getFirst());
        return new Tournament.PublicDetail(detail.tournament(),detail.teams().stream().map(team ->
            new Tournament.PublicTeam(team.id(),team.name(),team.roster().stream().map(Tournament.RosterPlayer::name).toList())).toList(),detail.fixtures(),detail.standings());
    }
    public List<UUID> joined(UUID account) {
        return jdbc.queryForList("SELECT tournament_id FROM tournament_followers WHERE account_id=? ORDER BY joined_at DESC",UUID.class,account);
    }
    public void join(UUID id, UUID account) {
        if(jdbc.queryForObject("SELECT count(*) FROM tournaments WHERE id=?",Integer.class,id)==0)
            throw new AuthFailure(404,"TOURNAMENT_NOT_FOUND","Tournament not found.");
        jdbc.update("INSERT INTO tournament_followers(tournament_id,account_id) VALUES (?,?) ON CONFLICT DO NOTHING",id,account);
    }
    public void leave(UUID id, UUID account) {
        jdbc.update("DELETE FROM tournament_followers WHERE tournament_id=? AND account_id=?",id,account);
    }
    public Tournament owned(UUID id, UUID owner, boolean lock) {
        var rows=jdbc.query("SELECT * FROM tournaments WHERE id=? AND owner_account_id=?"+(lock?" FOR UPDATE":""),ROW,id,owner);
        if(rows.isEmpty()) throw new AuthFailure(404,"TOURNAMENT_NOT_FOUND","Tournament not found in your account.");
        return rows.getFirst();
    }
    public void create(Tournament value, UUID owner) {
        jdbc.update("INSERT INTO tournaments(id,owner_account_id,name,venue,starts_on,half_minutes,raid_seconds) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
            value.id(),owner,value.name(),value.venue(),value.startsOn(),value.halfMinutes(),value.raidSeconds());
        if(!owned(value.id(),owner,true).equals(value)) throw new AuthFailure(409,"ID_REUSED","Tournament ID was reused with different details.");
    }
    public Tournament.Detail detail(UUID id, UUID owner) {
        var tournament=owned(id,owner,false);
        var teams=jdbc.query("SELECT id,name,roster_revision FROM tournament_teams WHERE tournament_id=? ORDER BY name,id",(r,i)->{
            var teamId=r.getObject("id",UUID.class);
            var roster=jdbc.query("SELECT name,phone FROM tournament_roster_players WHERE team_id=? ORDER BY position",(player,index)->new Tournament.RosterPlayer(player.getString("name"),player.getString("phone")),teamId);
            return new Tournament.Team(teamId,r.getString("name"),r.getInt("roster_revision"),roster);
        },id);
        var fixtures=jdbc.query("""
            SELECT f.*,m.projection->>'status' AS status,(m.projection #>> '{scores,0}')::integer AS score_a,
              (m.projection #>> '{scores,1}')::integer AS score_b,m.projection->>'phase' AS phase,
              (m.projection #>> '{tieScores,0}')::integer AS tie_a,(m.projection #>> '{tieScores,1}')::integer AS tie_b,m.projection->>'winner' AS winner
            FROM tournament_fixtures f LEFT JOIN matches m ON m.id=f.match_id
            WHERE f.tournament_id=? ORDER BY f.scheduled_at NULLS LAST,f.created_at,f.id
            """,(r,i)->new Tournament.Fixture(r.getObject("id",UUID.class),r.getObject("team_a_id",UUID.class),r.getObject("team_b_id",UUID.class),
                r.getTimestamp("scheduled_at")==null?null:r.getTimestamp("scheduled_at").toInstant(),r.getObject("match_id",UUID.class),
                r.getString("status"),r.getObject("score_a",Integer.class),r.getObject("score_b",Integer.class),r.getString("phase"),
                r.getObject("tie_a",Integer.class),r.getObject("tie_b",Integer.class),r.getString("winner"),r.getInt("schedule_revision")),id);
        return new Tournament.Detail(tournament,teams,fixtures,com.raidzon.tournament.service.TournamentStandings.calculate(teams,fixtures));
    }
    public void team(UUID id, Tournament.TeamInput team) {
        var existing=jdbc.queryForList("SELECT name FROM tournament_teams WHERE id=? AND tournament_id=?",String.class,team.id(),id);
        if(!existing.isEmpty()) { if(!existing.getFirst().equals(team.name())) conflict("Team ID was reused."); return; }
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE id=?",Integer.class,team.id())>0)conflict("Team ID was reused.");
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id=?",Integer.class,id)>=64) conflict("A tournament supports up to 64 teams.");
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id=? AND lower(name)=lower(?)",Integer.class,id,team.name())>0) conflict("That team is already registered.");
        jdbc.update("INSERT INTO tournament_teams(id,tournament_id,name) VALUES (?,?,?)",team.id(),id,team.name());
    }
    public void roster(UUID tournamentId,UUID teamId,Tournament.RosterInput input) {
        var teams=jdbc.query("SELECT roster_revision FROM tournament_teams WHERE id=? AND tournament_id=? FOR UPDATE",
            (r,i)->r.getInt("roster_revision"),teamId,tournamentId);
        if(teams.isEmpty())throw new AuthFailure(404,"TEAM_NOT_FOUND","Team not found.");
        int revision=teams.getFirst();
        var current=jdbc.query("SELECT name,phone FROM tournament_roster_players WHERE team_id=? ORDER BY position",
            (r,i)->new Tournament.RosterPlayer(r.getString("name"),r.getString("phone")),teamId);
        if(revision==input.expectedRevision() && current.equals(input.players()))return;
        if(revision==input.expectedRevision()+1 && current.equals(input.players()))return;
        if(revision!=input.expectedRevision())conflict("Team roster changed. Refresh before editing it again.");
        for(var player:input.players()) {
            var owner=jdbc.query("SELECT team_id FROM tournament_roster_players WHERE tournament_id=? AND phone=? AND team_id<>?",
                (r,i)->r.getObject("team_id",UUID.class),tournamentId,player.phone(),teamId);
            if(!owner.isEmpty())conflict("This phone number is already on another team in the tournament.");
        }
        jdbc.update("DELETE FROM tournament_roster_players WHERE team_id=?",teamId);
        for(int index=0;index<input.players().size();index++){
            var player=input.players().get(index);
            jdbc.update("INSERT INTO tournament_roster_players(tournament_id,team_id,position,name,phone) VALUES (?,?,?,?,?)",
                tournamentId,teamId,index,player.name(),player.phone());
        }
        jdbc.update("UPDATE tournament_teams SET roster_revision=roster_revision+1 WHERE id=?",teamId);
    }
    public void fixture(UUID id, Tournament.FixtureInput fixture) {
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id=? AND id IN (?,?)",Integer.class,id,fixture.teamAId(),fixture.teamBId())!=2)
            conflict("Choose two different teams registered in this tournament.");
        var existing=jdbc.query("SELECT * FROM tournament_fixtures WHERE id=? AND tournament_id=?",(r,i)->new Tournament.FixtureInput(r.getObject("id",UUID.class),r.getObject("team_a_id",UUID.class),r.getObject("team_b_id",UUID.class),r.getTimestamp("scheduled_at")==null?null:r.getTimestamp("scheduled_at").toInstant()),fixture.id(),id);
        if(!existing.isEmpty()){if(!existing.getFirst().equals(fixture))conflict("Fixture ID was reused.");return;}
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_fixtures WHERE id=?",Integer.class,fixture.id())>0)conflict("Fixture ID was reused.");
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_fixtures WHERE tournament_id=?",Integer.class,id)>=1000)conflict("Fixture limit reached.");
        jdbc.update("INSERT INTO tournament_fixtures(id,tournament_id,team_a_id,team_b_id,scheduled_at) VALUES (?,?,?,?,?)",fixture.id(),id,fixture.teamAId(),fixture.teamBId(),fixture.scheduledAt()==null?null:Timestamp.from(fixture.scheduledAt()));
    }
    public void link(UUID id, UUID fixture, UUID match, UUID owner) {
        var rows=jdbc.queryForList("""
            SELECT f.match_id,a.name AS team_a,b.name AS team_b FROM tournament_fixtures f
            JOIN tournament_teams a ON a.id=f.team_a_id JOIN tournament_teams b ON b.id=f.team_b_id
            WHERE f.id=? AND f.tournament_id=? FOR UPDATE OF f
            """,fixture,id);
        if(rows.isEmpty()) throw new AuthFailure(404,"FIXTURE_NOT_FOUND","Fixture not found.");
        var row=rows.getFirst();
        if(row.get("match_id")!=null){if(!row.get("match_id").equals(match))conflict("This fixture already has a match.");return;}
        var matches=jdbc.queryForList("SELECT projection FROM matches WHERE id=? AND owner_account_id=? FOR UPDATE",match,owner);
        if(matches.isEmpty())throw new AuthFailure(404,"MATCH_NOT_FOUND","Sync a match owned by this account first.");
        int valid=jdbc.queryForObject("""
            SELECT count(*) FROM matches m JOIN tournaments t ON t.id=? WHERE m.id=?
            AND m.projection #>> '{teams,0,name}'=? AND m.projection #>> '{teams,1,name}'=?
            AND (m.projection->>'halfMinutes')::integer=t.half_minutes AND (m.projection->>'raidSeconds')::integer=t.raid_seconds
            """,Integer.class,id,match,row.get("team_a"),row.get("team_b"));
        if(valid!=1)conflict("Team order, names and match timers must match the tournament fixture.");
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_fixtures WHERE match_id=?",Integer.class,match)>0)conflict("This match is already linked to a fixture.");
        jdbc.update("UPDATE tournament_fixtures SET match_id=? WHERE id=?",match,fixture);
    }
    public void reschedule(UUID tournamentId, UUID fixtureId, Tournament.ScheduleInput input) {
        var rows=jdbc.query("SELECT scheduled_at,schedule_revision FROM tournament_fixtures WHERE id=? AND tournament_id=? FOR UPDATE",
            (r,i)->new Tournament.ScheduleInput(r.getTimestamp("scheduled_at")==null?null:r.getTimestamp("scheduled_at").toInstant(),r.getInt("schedule_revision")),fixtureId,tournamentId);
        if(rows.isEmpty())throw new AuthFailure(404,"FIXTURE_NOT_FOUND","Fixture not found.");
        var current=rows.getFirst();
        if(input.expectedRevision()==current.expectedRevision()) {
            if(java.util.Objects.equals(input.scheduledAt(),current.scheduledAt()))return;
            jdbc.update("UPDATE tournament_fixtures SET scheduled_at=?,schedule_revision=schedule_revision+1 WHERE id=?",
                input.scheduledAt()==null?null:Timestamp.from(input.scheduledAt()),fixtureId);
            return;
        }
        if(input.expectedRevision()+1==current.expectedRevision() && java.util.Objects.equals(input.scheduledAt(),current.scheduledAt()))return;
        conflict("Fixture schedule changed. Refresh before editing it again.");
    }
    private static void conflict(String message){throw new AuthFailure(409,"TOURNAMENT_CONFLICT",message);}
}
