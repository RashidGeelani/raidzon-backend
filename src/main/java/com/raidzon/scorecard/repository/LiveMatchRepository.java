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
    public static ObjectNode publicState(JsonNode source,ObjectMapper json){
        var target=json.createObjectNode();
        for(String field:new String[]{"scores","tieScores","status","phase","half","raidNumber","turn","currentRaiderId","clock","raidClock","winner","tieBreakerRaiders","tieRaids"})
            target.set(field,source.path(field));
        var teams=target.putArray("teams");
        for(var team:source.path("teams")){
            var output=teams.addObject();output.put("name",team.path("name").asText());
            var players=output.putArray("players");
            for(var player:team.path("players")){
                var p=players.addObject();
                for(String field:new String[]{"id","name","status","raidPoints","tacklePoints"})p.set(field,player.path(field));
            }
        }
        return target;
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public LiveMatchView read(UUID id){
        var rows=jdbc.queryForList("""
            SELECT projection,version,updated_at FROM matches m WHERE id=? AND (
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
        return new LiveMatchView(id,publicState(parse(row.get("projection").toString()),json),events,
            ((Number)row.get("version")).intValue(),System.currentTimeMillis(),((java.sql.Timestamp)row.get("updated_at")).getTime());
    }
}
