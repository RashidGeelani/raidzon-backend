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
            SELECT p.id,COALESCE(p.display_name,p.initial_name) AS initial_name,count(DISTINCT m.id) AS played,
                COALESCE(sum((player.value->>'raidPoints')::bigint),0) AS raid_points,
                COALESCE(sum((player.value->>'tacklePoints')::bigint),0) AS tackle_points,
                COALESCE(sum((player.value->>'raidPoints')::bigint + (player.value->>'tacklePoints')::bigint),0) AS total_points
            FROM match_player_links l JOIN player_profiles p ON p.id=l.profile_id JOIN matches m ON m.id=l.match_id
            CROSS JOIN LATERAL jsonb_array_elements(m.projection->'teams') AS team(value)
            CROSS JOIN LATERAL jsonb_array_elements(team.value->'players') AS player(value)
            WHERE player.value->>'id'=l.local_player_id::text AND m.projection->>'status'='COMPLETED'
                AND EXISTS(SELECT 1 FROM tournament_fixtures f WHERE f.match_id=m.id AND (?::uuid IS NULL OR f.tournament_id=?))
            GROUP BY p.id,p.display_name,p.initial_name ORDER BY
            """+order+" DESC,2,p.id LIMIT 100",
            (r,i)->new Tournament.PlayerRanking(r.getObject("id",UUID.class),r.getString("initial_name"),r.getLong("played"),r.getLong("raid_points"),r.getLong("tackle_points")),tournamentId,tournamentId);
    }
    public Tournament.PublicDetail publicDetail(UUID id) {
        var owners=jdbc.queryForList("SELECT owner_account_id FROM tournaments WHERE id=?",UUID.class,id);
        if(owners.isEmpty()) throw new AuthFailure(404,"TOURNAMENT_NOT_FOUND","Tournament not found.");
        var detail=detail(id,owners.getFirst());
        // A player's own name (set after verifying their phone) replaces the organizer's roster entry.
        var ownNames=new java.util.HashMap<String,String>();
        jdbc.query("""
            SELECT r.phone,p.display_name FROM tournament_roster_players r JOIN player_profiles p ON p.phone=r.phone
            WHERE r.tournament_id=? AND p.display_name IS NOT NULL
            """,(org.springframework.jdbc.core.RowCallbackHandler) r->ownNames.put(r.getString("phone"),r.getString("display_name")),id);
        return new Tournament.PublicDetail(detail.tournament(),detail.teams().stream().map(team ->
            new Tournament.PublicTeam(team.id(),team.name(),team.roster().stream().map(player->ownNames.getOrDefault(player.phone(),player.name())).toList())).toList(),
            detail.fixtures(),detail.standings(),detail.registrationOpen(),detail.format(),detail.groups(),detail.championId());
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
        var teams=jdbc.query("SELECT id,name,roster_revision,team_id FROM tournament_teams WHERE tournament_id=? ORDER BY name,id",(r,i)->{
            var teamId=r.getObject("id",UUID.class);
            var roster=jdbc.query("SELECT name,phone FROM tournament_roster_players WHERE team_id=? ORDER BY position",(player,index)->new Tournament.RosterPlayer(player.getString("name"),player.getString("phone")),teamId);
            return new Tournament.Team(teamId,r.getString("name"),r.getInt("roster_revision"),roster,r.getObject("team_id",UUID.class));
        },id);
        var fixtures=jdbc.query("""
            SELECT f.*,m.projection->>'status' AS status,(m.projection #>> '{scores,0}')::integer AS score_a,
              (m.projection #>> '{scores,1}')::integer AS score_b,m.projection->>'phase' AS phase,
              (m.projection #>> '{tieScores,0}')::integer AS tie_a,(m.projection #>> '{tieScores,1}')::integer AS tie_b,m.projection->>'winner' AS winner
            FROM tournament_fixtures f LEFT JOIN matches m ON m.id=f.match_id
            WHERE f.tournament_id=? ORDER BY CASE WHEN f.stage IN ('KNOCKOUT','THIRD_PLACE') THEN 1 ELSE 0 END,f.round NULLS FIRST,
              CASE WHEN f.stage='THIRD_PLACE' THEN 0 ELSE 1 END,f.scheduled_at NULLS LAST,f.slot,f.created_at,f.id
            """,(r,i)->new Tournament.Fixture(r.getObject("id",UUID.class),r.getObject("team_a_id",UUID.class),r.getObject("team_b_id",UUID.class),
                r.getTimestamp("scheduled_at")==null?null:r.getTimestamp("scheduled_at").toInstant(),r.getObject("match_id",UUID.class),
                r.getString("status"),r.getObject("score_a",Integer.class),r.getObject("score_b",Integer.class),r.getString("phase"),
                r.getObject("tie_a",Integer.class),r.getObject("tie_b",Integer.class),r.getString("winner"),r.getInt("schedule_revision"),
                r.getString("stage"),r.getObject("group_id",UUID.class),r.getObject("round",Integer.class),r.getObject("slot",Integer.class),
                r.getString("source_a"),r.getString("source_b"),null,null,null),id);
        var settings=jdbc.queryForMap("SELECT registration_open,format,group_count,advance_per_group,third_place FROM tournaments WHERE id=?",id);
        boolean open=Boolean.TRUE.equals(settings.get("registration_open"));
        String type=(String)settings.get("format");
        int advance=((Number)settings.get("advance_per_group")).intValue();
        var groups=groups(id);
        var teamNames=new java.util.HashMap<UUID,String>();
        for(var team:teams)teamNames.put(team.id(),team.name());
        var groupNames=new java.util.HashMap<UUID,String>();
        for(var group:groups)groupNames.put(group.id(),group.name());
        var standings=new java.util.ArrayList<Tournament.Standing>();
        var groupTables=new java.util.HashMap<UUID,List<Tournament.Standing>>();
        var complete=new java.util.HashSet<UUID>();
        if("GROUPS_KNOCKOUT".equals(type)){
            for(var group:groups){
                var members=teams.stream().filter(t->group.teamIds().contains(t.id())).toList();
                var played=fixtures.stream().filter(f->"GROUP".equals(f.stage()) && group.id().equals(f.groupId())).toList();
                var table=com.raidzon.tournament.service.TournamentStandings.calculate(members,played,group.id(),advance);
                groupTables.put(group.id(),table);standings.addAll(table);
                if(!played.isEmpty() && played.stream().allMatch(Tournament.Fixture::completed))complete.add(group.id());
            }
        } else if("LEAGUE".equals(type)) standings.addAll(com.raidzon.tournament.service.TournamentStandings.calculate(teams,fixtures));
        var resolved=com.raidzon.tournament.service.TournamentFormats.resolve(fixtures,teamNames,groupNames,groupTables,complete);
        boolean locked=fixtures.stream().anyMatch(f->f.matchId()!=null);
        boolean generated=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tournament_fixtures WHERE tournament_id=? AND generated)",Boolean.class,id));
        var format=new Tournament.Format(type,((Number)settings.get("group_count")).intValue(),advance,Boolean.TRUE.equals(settings.get("third_place")),locked,generated);
        return new Tournament.Detail(tournament,teams,resolved,standings,open,format,groups,com.raidzon.tournament.service.TournamentFormats.champion(resolved));
    }
    /** Groups in order, each with its teams in draw order. League and knockout return one unnamed entry with the draw order. */
    public List<Tournament.Group> groups(UUID id){
        var rows=jdbc.queryForList("SELECT id,name FROM tournament_groups WHERE tournament_id=? ORDER BY position",id);
        if(rows.isEmpty())
            return List.of(new Tournament.Group(null,"",jdbc.queryForList("SELECT id FROM tournament_teams WHERE tournament_id=? ORDER BY seed NULLS LAST,name,id",UUID.class,id)));
        return rows.stream().map(row->new Tournament.Group((UUID)row.get("id"),(String)row.get("name"),
            jdbc.queryForList("SELECT id FROM tournament_teams WHERE group_id=? ORDER BY seed NULLS LAST,name,id",UUID.class,row.get("id")))).toList();
    }
    public boolean locked(UUID id){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tournament_fixtures WHERE tournament_id=? AND match_id IS NOT NULL)",Boolean.class,id));
    }
    public Tournament.FormatInput format(UUID id){
        return jdbc.queryForObject("SELECT format,group_count,advance_per_group,third_place FROM tournaments WHERE id=?",
            (r,i)->new Tournament.FormatInput(r.getString(1),r.getInt(2),r.getInt(3),r.getBoolean(4)),id);
    }
    /** Saves the format, clears unplayed fixtures and (for groups) creates groups A, B, … with teams spread evenly. */
    public void setFormat(UUID id,Tournament.FormatInput input){
        jdbc.update("UPDATE tournaments SET format=?,group_count=?,advance_per_group=?,third_place=? WHERE id=?",
            input.type(),input.groupCount(),input.advancePerGroup(),input.thirdPlace(),id);
        jdbc.update("DELETE FROM tournament_fixtures WHERE tournament_id=? AND match_id IS NULL",id);
        jdbc.update("UPDATE tournament_teams SET group_id=NULL WHERE tournament_id=?",id);
        jdbc.update("DELETE FROM tournament_groups WHERE tournament_id=?",id);
        if(!"GROUPS_KNOCKOUT".equals(input.type()))return;
        var groupIds=new java.util.ArrayList<UUID>();
        for(int i=0;i<input.groupCount();i++){
            var groupId=UUID.randomUUID();groupIds.add(groupId);
            jdbc.update("INSERT INTO tournament_groups(id,tournament_id,name,position) VALUES (?,?,?,?)",groupId,id,String.valueOf((char)('A'+i)),i);
        }
        // Snake order (A B C C B A …) keeps groups balanced when teams are listed by strength.
        var teams=jdbc.queryForList("SELECT id FROM tournament_teams WHERE tournament_id=? ORDER BY seed NULLS LAST,name,id",UUID.class,id);
        int n=groupIds.size();
        for(int i=0;i<teams.size();i++){
            int lap=i/n,pos=i%n;
            jdbc.update("UPDATE tournament_teams SET group_id=?,seed=? WHERE id=?",groupIds.get(lap%2==0?pos:n-1-pos),lap,teams.get(i));
        }
    }
    /** Applies the organizer's arrangement (drag and drop): group membership and draw order. Clears unplayed fixtures. */
    public void arrange(UUID id,Tournament.ArrangementInput input,boolean groupsFormat){
        var teamIds=new java.util.HashSet<>(jdbc.queryForList("SELECT id FROM tournament_teams WHERE tournament_id=?",UUID.class,id));
        var seen=new java.util.HashSet<UUID>();
        var known=new java.util.HashSet<>(jdbc.queryForList("SELECT id FROM tournament_groups WHERE tournament_id=?",UUID.class,id));
        if(input==null || input.groups()==null)throw new IllegalArgumentException("Send the groups and their teams.");
        for(var group:input.groups()){
            if(group==null || group.teamIds()==null)throw new IllegalArgumentException("Send the groups and their teams.");
            if(groupsFormat ? group.id()==null || !known.contains(group.id()) : group.id()!=null)conflict("Groups changed. Refresh and try again.");
            for(var team:group.teamIds())if(!teamIds.contains(team) || !seen.add(team))conflict("Each team must appear exactly once.");
        }
        if(!seen.equals(teamIds))conflict("Every team must be placed. Refresh and try again.");
        for(var group:input.groups())
            for(int i=0;i<group.teamIds().size();i++)
                jdbc.update("UPDATE tournament_teams SET group_id=?,seed=? WHERE id=?",group.id(),i,group.teamIds().get(i));
        jdbc.update("DELETE FROM tournament_fixtures WHERE tournament_id=? AND match_id IS NULL AND generated",id);
    }
    /** Replaces all unplayed fixtures with the generated plan. */
    public void replaceFixtures(UUID id,List<com.raidzon.tournament.service.TournamentFormats.Planned> plan){
        if(plan.size()>1000)conflict("Fixture limit reached.");
        jdbc.update("DELETE FROM tournament_fixtures WHERE tournament_id=? AND match_id IS NULL",id);
        for(var f:plan)
            jdbc.update("""
                INSERT INTO tournament_fixtures(id,tournament_id,team_a_id,team_b_id,stage,group_id,round,slot,source_a,source_b,generated)
                VALUES (?,?,?,?,?,?,?,?,?,?,true)
                """,f.id(),id,f.teamA(),f.teamB(),f.stage(),f.groupId(),f.round(),f.slot(),f.sourceA(),f.sourceB());
    }
    /** Stores a knockout fixture's teams once they are decided, so the match can be linked and validated. */
    public void fillTeams(UUID tournament,UUID fixture,UUID teamA,UUID teamB){
        jdbc.update("UPDATE tournament_fixtures SET team_a_id=COALESCE(team_a_id,?),team_b_id=COALESCE(team_b_id,?) WHERE id=? AND tournament_id=? AND match_id IS NULL",
            teamA,teamB,fixture,tournament);
    }
    public void team(UUID id, Tournament.TeamInput team) {
        var existing=jdbc.queryForList("SELECT name FROM tournament_teams WHERE id=? AND tournament_id=?",String.class,team.id(),id);
        if(!existing.isEmpty()) { if(!existing.getFirst().equals(team.name())) conflict("Team ID was reused."); return; }
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE id=?",Integer.class,team.id())>0)conflict("Team ID was reused.");
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id=?",Integer.class,id)>=64) conflict("A tournament supports up to 64 teams.");
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id=? AND lower(name)=lower(?)",Integer.class,id,team.name())>0) conflict("That team is already registered.");
        jdbc.update("INSERT INTO tournament_teams(id,tournament_id,name) VALUES (?,?,?)",team.id(),id,team.name());
        // New teams join the end of the draw and, in a groups tournament, the group with the fewest teams.
        jdbc.update("""
            UPDATE tournament_teams t SET seed=(SELECT COALESCE(max(seed),-1)+1 FROM tournament_teams WHERE tournament_id=? AND id<>?),
              group_id=(SELECT g.id FROM tournament_groups g WHERE g.tournament_id=?
                        ORDER BY (SELECT count(*) FROM tournament_teams m WHERE m.group_id=g.id),g.position LIMIT 1)
            WHERE t.id=?
            """,id,team.id(),id,team.id());
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
        replaceRoster(tournamentId,teamId,input.players());
    }
    /** Writes a roster snapshot. Each player is linked to their global profile (created when new). */
    private void replaceRoster(UUID tournamentId,UUID teamId,List<Tournament.RosterPlayer> players){
        jdbc.update("DELETE FROM tournament_roster_players WHERE team_id=?",teamId);
        for(int index=0;index<players.size();index++){
            var player=players.get(index);
            jdbc.update("""
                INSERT INTO player_profiles(id,phone,initial_name,claimed_by)
                VALUES (?,?,?,(SELECT id FROM user_accounts WHERE phone=?)) ON CONFLICT(phone) DO NOTHING
                """,UUID.randomUUID(),player.phone(),player.name(),player.phone());
            jdbc.update("""
                INSERT INTO tournament_roster_players(tournament_id,team_id,position,name,phone,profile_id)
                SELECT ?,?,?,?,?,id FROM player_profiles WHERE phone=?
                """,tournamentId,teamId,index,player.name(),player.phone(),player.phone());
        }
        jdbc.update("UPDATE tournament_teams SET roster_revision=roster_revision+1 WHERE id=?",teamId);
    }
    /** Players already registered to a different team in this tournament (one team per player per tournament). */
    public List<String> playersOnOtherTeams(UUID tournamentId,UUID teamId,List<Tournament.RosterPlayer> players){
        if(players.isEmpty())return List.of();
        var phones=players.stream().map(Tournament.RosterPlayer::phone).toArray(String[]::new);
        return jdbc.queryForList("""
            SELECT r.name||' ('||t.name||')' FROM tournament_roster_players r JOIN tournament_teams t ON t.id=r.team_id
            WHERE r.tournament_id=? AND r.team_id<>? AND r.phone = ANY(?) ORDER BY r.name
            """,String.class,tournamentId,teamId,phones);
    }
    /** Creates (or, on retry, confirms) a tournament team registered from a saved team, then copies its squad. */
    public void registerSavedTeam(UUID tournamentId,UUID id,UUID savedTeamId,String name,List<Tournament.RosterPlayer> players){
        var existing=jdbc.queryForList("SELECT team_id FROM tournament_teams WHERE id=? AND tournament_id=?",id,tournamentId);
        if(!existing.isEmpty()){
            if(!savedTeamId.equals(existing.getFirst().get("team_id")))conflict("Team ID was reused.");
            return;
        }
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id=? AND team_id=?",Integer.class,tournamentId,savedTeamId)>0)
            conflict("This team is already registered in the tournament.");
        team(tournamentId,new Tournament.TeamInput(id,name));
        jdbc.update("UPDATE tournament_teams SET team_id=? WHERE id=?",savedTeamId,id);
        replaceRoster(tournamentId,id,players);
    }
    public UUID savedTeamOf(UUID tournamentId,UUID teamId){
        var rows=jdbc.queryForList("SELECT team_id FROM tournament_teams WHERE id=? AND tournament_id=? FOR UPDATE",teamId,tournamentId);
        if(rows.isEmpty())throw new AuthFailure(404,"TEAM_NOT_FOUND","Team not found.");
        var saved=(UUID)rows.getFirst().get("team_id");
        if(saved==null)conflict("This team was not registered from a saved team.");
        return saved;
    }
    public void syncSavedRoster(UUID tournamentId,UUID teamId,List<Tournament.RosterPlayer> players){
        var current=jdbc.query("SELECT name,phone FROM tournament_roster_players WHERE team_id=? ORDER BY position",
            (r,i)->new Tournament.RosterPlayer(r.getString("name"),r.getString("phone")),teamId);
        if(!current.equals(players))replaceRoster(tournamentId,teamId,players);
    }
    public void fixture(UUID id, Tournament.FixtureInput fixture) {
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_teams WHERE tournament_id=? AND id IN (?,?)",Integer.class,id,fixture.teamAId(),fixture.teamBId())!=2)
            conflict("Choose two different teams registered in this tournament.");
        var existing=jdbc.query("SELECT * FROM tournament_fixtures WHERE id=? AND tournament_id=?",(r,i)->new Tournament.FixtureInput(r.getObject("id",UUID.class),r.getObject("team_a_id",UUID.class),r.getObject("team_b_id",UUID.class),r.getTimestamp("scheduled_at")==null?null:r.getTimestamp("scheduled_at").toInstant()),fixture.id(),id);
        if(!existing.isEmpty()){if(!existing.getFirst().equals(fixture))conflict("Fixture ID was reused.");return;}
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_fixtures WHERE id=?",Integer.class,fixture.id())>0)conflict("Fixture ID was reused.");
        if(jdbc.queryForObject("SELECT count(*) FROM tournament_fixtures WHERE tournament_id=?",Integer.class,id)>=1000)conflict("Fixture limit reached.");
        if(!"LEAGUE".equals(format(id).type()))conflict("This tournament's fixtures come from its format. Use Generate fixtures.");
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
        // One team per player per tournament: every match player must be on their fixture team's roster.
        var fixtureTeams=jdbc.queryForMap("SELECT team_a_id,team_b_id FROM tournament_fixtures WHERE id=?",fixture);
        for(int side=0;side<2;side++){
            UUID team=(UUID)fixtureTeams.get(side==0?"team_a_id":"team_b_id");
            if(jdbc.queryForObject("SELECT count(*) FROM tournament_roster_players WHERE team_id=?",Integer.class,team)<Tournament.MIN_ROSTER)
                conflict("Save both teams' rosters before linking a match to this fixture.");
            var outsiders=jdbc.queryForList("""
                SELECT player->>'name' FROM matches m CROSS JOIN LATERAL jsonb_array_elements(m.projection->'teams'->?->'players') AS player
                WHERE m.id=? AND NOT EXISTS (SELECT 1 FROM tournament_roster_players r WHERE r.team_id=? AND r.phone=player->>'phone')
                ORDER BY 1
                """,String.class,side,match,team);
            if(!outsiders.isEmpty())conflict("Only registered players can play this fixture. Not on the "+(side==0?row.get("team_a"):row.get("team_b"))+" roster: "+String.join(", ",outsiders)+".");
        }
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
