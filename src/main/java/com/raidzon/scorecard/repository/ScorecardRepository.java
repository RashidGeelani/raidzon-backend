package com.raidzon.scorecard.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.scorecard.dto.PublicScorecard;
import com.raidzon.identity.service.AuthFailure;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository @Profile("postgres")
public class ScorecardRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public ScorecardRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public UUID publish(UUID matchId,UUID owner,boolean published){
        int changed=jdbc.update("""
            INSERT INTO public_scorecards(match_id,share_id,published)
            SELECT id,?,? FROM matches WHERE id=? AND (owner_account_id=? OR (? AND scoring_account_id=?))
            ON CONFLICT(match_id) DO UPDATE SET published=excluded.published
            """,UUID.randomUUID(),published,matchId,owner,published,owner);
        // The organizer can publish or stop sharing; a delegated scorer can share the live link too.
        if(changed!=1)throw new AuthFailure(403,"NOT_ORGANIZER","Only the match organizer can stop sharing its scorecard.");
        return jdbc.queryForObject("SELECT share_id FROM public_scorecards WHERE match_id=?",UUID.class,matchId);
    }
    public PublicScorecard read(UUID shareId){
        var rows=jdbc.query("""
            SELECT m.projection,m.version,m.updated_at FROM public_scorecards s JOIN matches m ON m.id=s.match_id
            WHERE s.share_id=? AND s.published AND m.removed_at IS NULL
            """,(rs,i)->{
                try {
                    var state=json.readTree(rs.getString("projection"));
                    // Explicit public allowlist. Never serialize the private match state or roster.
                    return new PublicScorecard(shareId,state.path("teams").path(0).path("name").asText(),state.path("teams").path(1).path("name").asText(),
                        state.path("scores").path(0).asInt(),state.path("scores").path(1).asInt(),state.path("tieScores").path(0).asInt(),state.path("tieScores").path(1).asInt(),
                        state.path("status").asText(),state.path("phase").asText(),state.path("half").asInt(),state.path("raidNumber").asInt(),
                        state.path("winner").isNull()?null:state.path("winner").asText(),rs.getInt("version"),rs.getTimestamp("updated_at").getTime());
                }catch(Exception error){throw new IllegalStateException("Unable to read scorecard.");}
            },shareId);
        if(rows.isEmpty())throw new AuthFailure(404,"SCORECARD_NOT_FOUND","This scorecard is unavailable or no longer shared.");
        return rows.getFirst();
    }
}
