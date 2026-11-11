package com.raidzon.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** "Continue scoring here": the scoring account moves a live match to another phone with a fresh OTP. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("postgres") @Import(AuthHttpTest.SmsConfig.class)
@EnabledIfEnvironmentVariable(named="RAIDZON_TEST_DATABASE_URL",matches=".+")
class MatchTakeoverHttpTest {
    @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired AuthHttpTest.TestSms sms;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("RAIDZON_TEST_DATABASE_URL"), user=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_USERNAME","raidzon_test"), password=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD","");
        var data=new PGSimpleDataSource();data.setURL(url);data.setUser(user);data.setPassword(password);
        String schema="test_takeover_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(data).execute("CREATE SCHEMA "+schema);
        properties.add("raidzon.database.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+schema);
        properties.add("raidzon.database.username",()->user);properties.add("raidzon.database.password",()->password);
        properties.add("raidzon.auth.pepper",()->"test-only-pepper-that-is-at-least-32-characters");
    }
    String phone(){return "+91"+String.format(Locale.ROOT,"%010d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),10_000_000_000L));}
    String secret(){return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    JsonNode post(String path,Object body,String token,int status) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1"+path).contentType("application/json").content(json.writeValueAsBytes(body));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return json.readTree(http.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    JsonNode get(String path,String token) throws Exception {
        return json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1"+path).header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    String login(String phone) throws Exception {
        UUID device=UUID.randomUUID();String key=secret();
        var challenge=post("/auth/challenges",Map.of("phone",phone,"deviceId",device,"deviceSecret",key),null,200);
        return post("/auth/verify",Map.of("challengeId",challenge.get("challengeId").asText(),"code",sms.codes.get(phone),"deviceId",device,"deviceSecret",key),null,200).get("token").asText();
    }
    /** Another session of the same account on `device`, verified at `verifiedAt` (a second phone, or a fresh OTP proof on it). */
    String session(String sameAccountAs,UUID device,long verifiedAt) {
        String token=secret();
        jdbc.update("INSERT INTO auth_devices(id,secret_hash) VALUES (?,?) ON CONFLICT DO NOTHING",device,AuthService.hash(secret()));
        jdbc.update("INSERT INTO auth_tokens(token_hash,account_id,device_id,expires_at,verified_at) SELECT ?,account_id,?,expires_at,? FROM auth_tokens WHERE token_hash=?",
            AuthService.hash(token),device,verifiedAt,AuthService.hash(sameAccountAs));
        return token;
    }
    UUID device(String token){return jdbc.queryForObject("SELECT device_id FROM auth_tokens WHERE token_hash=?",UUID.class,AuthService.hash(token));}
    Map<String,Object> setup(UUID matchId) {
        var teams=IntStream.range(0,2).mapToObj(side->Map.of("name","Team "+side,"players",IntStream.range(0,7).mapToObj(i->Map.of("id",UUID.randomUUID(),"name","Player "+side+i,"phone","")).toList())).toList();
        return Map.of("matchId",matchId,"teams",teams,"firstTurn",0,"halfMinutes",20,"raidSeconds",30,"startedAt",1000);
    }
    String ruleset;
    UUID match(String token,int events) throws Exception {
        UUID match=UUID.randomUUID();
        ruleset=post("/matches",setup(match),token,201).get("rulesetVersion").asText();
        for(int i=0;i<events;i++)post("/matches/"+match+"/events",event(i,2000+i*1000L),token,200);
        return match;
    }
    Map<String,Object> event(int baseVersion,long at){
        return Map.of("id",UUID.randomUUID(),"baseVersion",baseVersion,"rulesetVersion",ruleset,"occurredAt",at,"intent",Map.of("type","TECHNICAL","side",baseVersion%2));
    }
    Map<String,Object> proof(String token){return Map.of("verificationToken",token);}
    long now(){return System.currentTimeMillis();}

    @Test void ownerMovesLiveMatchWithFreshOtpAndOldPhoneIsLockedOut() throws Exception {
        String phoneA=login(phone());
        UUID match=match(phoneA,3);
        String history=jdbc.queryForObject("SELECT string_agg(event_id::text,',' ORDER BY sequence) FROM match_events WHERE match_id=?",String.class,match);
        String projection=jdbc.queryForObject("SELECT projection::text FROM matches WHERE id=?",String.class,match);
        // Phone B: the same account, signed in an hour ago. An old session alone can't take the match.
        UUID deviceB=UUID.randomUUID();
        String phoneB=session(phoneA,deviceB,now()-3_600_000);
        assertTrue(get("/account/dashboard",phoneB).path("recentMatches").get(0).path("scoringElsewhere").asBoolean());
        assertFalse(get("/account/dashboard",phoneA).path("recentMatches").get(0).path("scoringElsewhere").asBoolean());
        assertEquals("SCORING_MOVED",post("/matches/"+match+"/takeover",Map.of(),phoneB,403).path("code").asText());
        assertEquals("VERIFICATION_REQUIRED",post("/matches/"+match+"/takeover",proof(phoneB),phoneB,403).path("code").asText());
        assertEquals("VERIFICATION_REQUIRED",post("/matches/"+match+"/takeover",proof(session(phoneA,deviceB,now()-301_000)),phoneB,403).path("code").asText());
        // A fresh proof from the other phone doesn't count: it must be verified on the phone taking over.
        assertEquals("VERIFICATION_REQUIRED",post("/matches/"+match+"/takeover",proof(session(phoneA,device(phoneA),now())),phoneB,403).path("code").asText());
        // Someone else can't take it, whatever they verified.
        String stranger=login(phone());
        assertEquals("NOT_MATCH_SCORER",post("/matches/"+match+"/takeover",proof(session(stranger,device(stranger),now())),stranger,403).path("code").asText());
        assertEquals(projection,jdbc.queryForObject("SELECT projection::text FROM matches WHERE id=?",String.class,match));

        String fresh=session(phoneA,deviceB,now());
        var moved=post("/matches/"+match+"/takeover",proof(fresh),phoneB,200);
        assertEquals(3,moved.path("version").asInt());
        assertEquals(ruleset,moved.path("rulesetVersion").asText());
        List<String> ids=new ArrayList<>();moved.path("events").forEach(e->ids.add(e.path("id").asText()));
        assertEquals(history,String.join(",",ids));
        assertEquals(List.of(0,1,2),java.util.stream.StreamSupport.stream(moved.path("events").spliterator(),false).map(e->e.path("baseVersion").asInt()).toList());
        assertEquals("TECHNICAL",moved.path("events").get(0).path("intent").path("type").asText());
        assertEquals(json.readTree(projection).path("scores"),moved.path("state").path("scores"));
        assertEquals(0,moved.path("setup").path("scores").get(0).asInt());
        assertTrue(moved.path("fixture").isNull());
        // Spectators see the same match: nothing about the score or version changed.
        assertEquals(projection,jdbc.queryForObject("SELECT projection::text FROM matches WHERE id=?",String.class,match));
        // The proof is used up.
        assertEquals("VERIFICATION_REQUIRED",post("/matches/"+match+"/takeover",proof(fresh),phoneA,403).path("code").asText());

        // The old phone's next tap is refused with a code it can lock itself on; the new phone carries on.
        assertEquals("SCORING_MOVED",post("/matches/"+match+"/events",event(3,6000),phoneA,403).path("code").asText());
        post("/matches/"+match+"/events",event(3,6000),phoneB,200);
        // The new phone may read the history again without another OTP (a retried import).
        assertEquals(4,post("/matches/"+match+"/takeover",Map.of(),phoneB,200).path("events").size());
        assertTrue(get("/account/dashboard",phoneA).path("recentMatches").get(0).path("scoringElsewhere").asBoolean());

        // Phone A takes it back the same way, which fences phone B.
        post("/matches/"+match+"/takeover",proof(session(phoneA,device(phoneA),now())),phoneA,200);
        assertEquals("SCORING_MOVED",post("/matches/"+match+"/events",event(4,7000),phoneB,403).path("code").asText());
        post("/matches/"+match+"/events",event(4,7000),phoneA,200);

        // Every attempt is kept: who, which match, from which device to which, and the result.
        var audit=jdbc.queryForList("SELECT from_session,to_session,result FROM match_takeovers WHERE match_id=? ORDER BY id",match);
        assertEquals(List.of("VERIFICATION_REQUIRED","VERIFICATION_REQUIRED","VERIFICATION_REQUIRED","NOT_MATCH_SCORER","MOVED","VERIFICATION_REQUIRED","MOVED"),
            audit.stream().map(r->r.get("result")).toList());
        assertEquals(device(phoneA),audit.get(4).get("from_session"));assertEquals(deviceB,audit.get(4).get("to_session"));
        // Six attempts by the owner in the last hour: the next one is refused before the proof is looked at.
        String spare=session(phoneA,deviceB,now());
        assertEquals("RATE_LIMITED",post("/matches/"+match+"/takeover",proof(spare),phoneB,429).path("code").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE token_hash=? AND NOT revoked",Integer.class,AuthService.hash(spare)));
    }

    @Test void finishedRemovedAndDeletedMatchesCannotBeTakenOver() throws Exception {
        String phoneA=login(phone());
        UUID deviceB=UUID.randomUUID();String phoneB=session(phoneA,deviceB,now()-3_600_000);
        UUID finished=match(phoneA,1),removed=match(phoneA,1),deleted=match(phoneA,0);
        jdbc.update("UPDATE matches SET projection=jsonb_set(jsonb_set(projection,'{status}','\"COMPLETED\"'),'{winner}','\"TEAM_A\"') WHERE id=?",finished);
        UUID account=jdbc.queryForObject("SELECT account_id FROM auth_tokens WHERE token_hash=?",UUID.class,AuthService.hash(phoneA));
        jdbc.update("UPDATE matches SET removed_at=now(),removed_by=? WHERE id=?",account,removed);
        jdbc.update("UPDATE matches SET deleted_at=now() WHERE id=?",deleted);
        String fresh=session(phoneA,deviceB,now());
        assertFalse(get("/account/dashboard",phoneB).path("recentMatches").findValues("scoringElsewhere").stream().anyMatch(JsonNode::asBoolean));
        assertEquals("MATCH_FINISHED",post("/matches/"+finished+"/takeover",proof(fresh),phoneB,409).path("code").asText());
        // A refused takeover leaves the proof unused, so the scorer isn't asked for another code.
        assertEquals("MATCH_REMOVED",post("/matches/"+removed+"/takeover",proof(fresh),phoneB,409).path("code").asText());
        assertEquals("MATCH_DELETED",post("/matches/"+deleted+"/takeover",proof(fresh),phoneB,410).path("code").asText());
        assertEquals(device(phoneA),jdbc.queryForObject("SELECT scoring_session_id FROM matches WHERE id=?",UUID.class,finished));
        assertEquals("NOT_FOUND",post("/matches/"+UUID.randomUUID()+"/takeover",proof(fresh),phoneB,404).path("code").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE token_hash=? AND NOT revoked",Integer.class,AuthService.hash(fresh)));
        assertEquals("INVALID_REQUEST",post("/matches/"+finished+"/takeover",Map.of("verificationToken",fresh,"deviceId",deviceB),phoneB,422).path("code").asText());
    }
}
