package com.raidzon.assignment.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.identity.service.AuthFailure;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.sql.DataSource;
import java.util.*;
import com.raidzon.assignment.dto.Assignment;

@Repository @Profile("postgres")
public class ScorerAssignmentRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public ScorerAssignmentRepository(DataSource data,ObjectMapper json) {
        this.jdbc=new JdbcTemplate(data);this.tx=new TransactionTemplate(new DataSourceTransactionManager(data));this.json=json;
    }

    public List<Assignment> list(AuthIdentity actor) {
        return jdbc.query("""
            SELECT m.id,m.ruleset_version,m.projection #>> '{teams,0,name}' AS team_a,m.projection #>> '{teams,1,name}' AS team_b,a.accepted
            FROM match_scorer_assignments a JOIN matches m ON m.id=a.match_id
            WHERE a.scorer_account_id=? AND m.deleted_at IS NULL ORDER BY a.created_at DESC LIMIT 100
            """,(rs,i)->new Assignment(rs.getObject("id",UUID.class),rs.getString("team_a"),rs.getString("team_b"),rs.getBoolean("accepted"),rs.getString("ruleset_version")),actor.accountId());
    }
    public void assign(UUID matchId,String phone,AuthIdentity actor) {
        if(phone==null || !phone.matches("\\+[1-9][0-9]{7,14}"))throw new AuthFailure(400,"INVALID_PHONE","Use an international phone number.");
        tx.executeWithoutResult(status->{
            var match=lock(matchId);
            if(!actor.accountId().equals(match.get("owner_account_id")))throw new AuthFailure(403,"NOT_ORGANIZER","Only the match organizer can assign its scorer.");
            if(((Number)match.get("version")).intValue()!=0)throw new AuthFailure(409,"MATCH_STARTED","Assign a scorer before recording match events.");
            var recipients=jdbc.queryForList("SELECT id FROM user_accounts WHERE phone=?",UUID.class,phone);
            if(recipients.isEmpty())throw new AuthFailure(400,"SCORER_NOT_REGISTERED","The scorer must sign in with this number first.");
            jdbc.update("""
                INSERT INTO match_scorer_assignments(match_id,scorer_account_id) VALUES (?,?)
                ON CONFLICT(match_id) DO UPDATE SET scorer_account_id=excluded.scorer_account_id,accepted=false,created_at=now()
                """,matchId,recipients.getFirst());
            // Immediately fence out the old scorer, including any delayed offline uploads.
            jdbc.update("UPDATE matches SET scoring_account_id=?,scoring_session_id=? WHERE id=?",recipients.getFirst(),UUID.randomUUID(),matchId);
        });
    }
    public JsonNode accept(UUID matchId,AuthIdentity actor) {
        return tx.execute(status->{
            var match=lock(matchId);
            var rows=jdbc.queryForList("SELECT * FROM match_scorer_assignments WHERE match_id=?",matchId);
            if(rows.isEmpty() || !actor.accountId().equals(rows.getFirst().get("scorer_account_id")))throw new AuthFailure(403,"NOT_ASSIGNED","This match is not assigned to your account.");
            boolean accepted=(Boolean)rows.getFirst().get("accepted");
            if(accepted && !actor.deviceId().equals(match.get("scoring_session_id")))throw new AuthFailure(409,"DEVICE_BOUND","The assignment was accepted on another device.");
            if(((Number)match.get("version")).intValue()!=0)throw new AuthFailure(409,"MATCH_STARTED","Continue this match on its original scoring device.");
            jdbc.update("UPDATE matches SET scoring_session_id=? WHERE id=?",actor.deviceId(),matchId);
            jdbc.update("UPDATE match_scorer_assignments SET accepted=true WHERE match_id=?",matchId);
            try{return json.readTree(match.get("initial_state").toString());}catch(Exception error){throw new IllegalStateException("Unable to read match setup.");}
        });
    }
    private Map<String,Object> lock(UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM matches WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new AuthFailure(404,"MATCH_NOT_FOUND","Match not found.");
        return rows.getFirst();
    }
}
