package com.raidzon.scorecard.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raidzon.identity.service.AuthFailure;
import com.raidzon.scorecard.dto.LiveMatchView;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Repository @Profile("postgres")
public class LiveMatchRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public LiveMatchRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private JsonNode parse(String value){try{return json.readTree(value);}catch(Exception error){throw new IllegalStateException("Unable to read match view.",error);}}
    public static ObjectNode publicState(JsonNode source,ObjectMapper json){return publicState(source,json,java.util.Map.of());}
    /** ownNames maps match-local player IDs to the player's own profile name, which replaces the stored roster name. */
    public static ObjectNode publicState(JsonNode source,ObjectMapper json,java.util.Map<String,String> ownNames){
        var target=json.createObjectNode();
        for(String field:new String[]{"scores","tieScores","status","phase","half","raidNumber","turn","currentRaiderId","clock","raidClock","winner","tieBreakerRaiders","tieRaids"})
            target.set(field,source.path(field));
        // v5 Do-or-Die counter (absent for older rulesets) so watchers can show the Do-or-Die banner.
        if(source.has("emptyRaids"))target.set("emptyRaids",source.get("emptyRaids"));
        var teams=target.putArray("teams");
        for(var team:source.path("teams")){
            var output=teams.addObject();output.put("name",team.path("name").asText());
            var players=output.putArray("players");
            for(var player:team.path("players")){
                var p=players.addObject();
                for(String field:new String[]{"id","name","status","raidPoints","tacklePoints"})p.set(field,player.path(field));
                if(player.has("jersey"))p.set("jersey",player.get("jersey")); // absent for matches created before numbers
                var ownName=ownNames.get(player.path("id").asText());
                if(ownName!=null)p.put("name",ownName);
            }
        }
        return target;
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public LiveMatchView read(UUID id){
        var rows=jdbc.queryForList("""
            SELECT projection,version,updated_at FROM matches m WHERE id=? AND removed_at IS NULL AND (
                EXISTS(SELECT 1 FROM tournament_fixtures f WHERE f.match_id=m.id)
                OR EXISTS(SELECT 1 FROM public_scorecards s WHERE s.match_id=m.id AND s.published))
            """,id);
        if(rows.isEmpty())throw new AuthFailure(404,"MATCH_NOT_PUBLIC","This match is not available to watch.");
        var row=rows.getFirst();
        var events=jdbc.query("""
            SELECT e.event_id,e.request->'intent'->>'type' AS type,e.summary,e.before_state->>'raidNumber' AS raid,e.components
            FROM match_events e WHERE e.match_id=? AND NOT EXISTS(
                SELECT 1 FROM match_events u WHERE u.match_id=e.match_id AND u.request #>> '{intent,type}'='UNDO'
                AND u.request #>> '{intent,targetEventId}'=e.event_id::text)
            ORDER BY e.sequence DESC LIMIT 30
            """,(r,i)->new LiveMatchView.Event(r.getObject("event_id",UUID.class),r.getString("type"),r.getString("summary"),r.getInt("raid"),parse(r.getString("components"))),id);
        var ownNames=new java.util.HashMap<String,String>();
        jdbc.query("""
            SELECT l.local_player_id::text AS player_id,p.display_name FROM match_player_links l
            JOIN player_profiles p ON p.id=l.profile_id WHERE l.match_id=? AND p.display_name IS NOT NULL
            """,(org.springframework.jdbc.core.RowCallbackHandler) r->ownNames.put(r.getString("player_id"),r.getString("display_name")),id);
        // Phone clock error: the smallest (server receive time - phone event time) over recent taps.
        // Network delay only adds to it, so the minimum is the best estimate; taps uploaded later
        // (offline scoring) are larger and ignored. Implausible values (over 10 min) count as 0.
        Long skew=jdbc.queryForObject("""
            SELECT min((extract(epoch FROM accepted_at)*1000)::bigint - (request->>'occurredAt')::bigint)
            FROM (SELECT accepted_at,request FROM match_events WHERE match_id=? ORDER BY sequence DESC LIMIT 30) recent
            """,Long.class,id);
        long clockOffset=skew==null||Math.abs(skew)>600_000?0:skew;
        return new LiveMatchView(id,publicState(parse(row.get("projection").toString()),json,ownNames),events,
            ((Number)row.get("version")).intValue(),System.currentTimeMillis(),((java.sql.Timestamp)row.get("updated_at")).getTime(),clockOffset);
    }
}
