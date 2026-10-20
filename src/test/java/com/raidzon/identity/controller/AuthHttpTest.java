package com.raidzon.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.service.SmsSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="raidzon.officials.enabled=true") @AutoConfigureMockMvc @ActiveProfiles("postgres") @Import(AuthHttpTest.SmsConfig.class)
@EnabledIfEnvironmentVariable(named="RAIDZON_TEST_DATABASE_URL",matches=".+")
class AuthHttpTest {
    @Autowired com.raidzon.identity.service.AuthService auth;
    @Test void widgetExchangeClaimsPhoneAndRejectsReplayAndDeviceImpersonation() {
        var widget=org.mockito.Mockito.mock(com.raidzon.identity.service.Msg91WidgetClient.class);
        String number=phone(), key=secret(); UUID device=UUID.randomUUID();
        org.mockito.Mockito.when(widget.verifyPhone(org.mockito.ArgumentMatchers.anyString())).thenReturn(number);
        String proof=UUID.randomUUID().toString();
        var session=auth.verifyWidget(proof,device,key,"widget-test",widget);
        assertEquals(device,auth.authenticate(session.token()).deviceId());
        assertEquals(number,jdbc.queryForObject("SELECT phone FROM user_accounts WHERE id=?",String.class,session.accountId()));
        assertThrows(com.raidzon.identity.service.AuthFailure.class,()->auth.verifyWidget(proof,device,key,"widget-test",widget));
        assertThrows(com.raidzon.identity.service.AuthFailure.class,()->auth.verifyWidget(UUID.randomUUID().toString(),device,secret(),"widget-test",widget));
        auth.logout(session.token());
        assertThrows(com.raidzon.identity.service.AuthFailure.class,()->auth.authenticate(session.token()));
    }
    @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired TestSms sms;
    static class TestSms implements SmsSender {
        final Map<String,String> codes=new ConcurrentHashMap<>();
        public boolean available(){return true;}
        public void sendCode(String phone,String code){codes.put(phone,code);}
    }
    @TestConfiguration static class SmsConfig { @Bean @Primary TestSms testSms(){return new TestSms();} }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("RAIDZON_TEST_DATABASE_URL"), user=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_USERNAME","raidzon_test"), password=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD","");
        var data=new PGSimpleDataSource();data.setURL(url);data.setUser(user);data.setPassword(password);
        String schema="test_auth_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(data).execute("CREATE SCHEMA "+schema);
        properties.add("raidzon.database.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+schema);
        properties.add("raidzon.database.username",()->user);properties.add("raidzon.database.password",()->password);
        properties.add("raidzon.auth.pepper",()->"test-only-pepper-that-is-at-least-32-characters");
        properties.add("RAIDZON_ADMIN_PHONES",()->ADMIN_PHONE);
    }
    static final String ADMIN_PHONE="+9170000"+String.format(Locale.ROOT,"%05d",new Random().nextInt(100_000));
    String phone(){return "+91"+String.format(Locale.ROOT,"%010d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),10_000_000_000L));}
    String secret(){return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    JsonNode postJson(String path,Object body,String token,int status) throws Exception {
        var request=post("/api/v1"+path).contentType("application/json").content(json.writeValueAsBytes(body));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return json.readTree(http.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    JsonNode challenge(String phone,UUID device,String secret) throws Exception {return postJson("/auth/challenges",Map.of("phone",phone,"deviceId",device,"deviceSecret",secret),null,200);}
    JsonNode verify(JsonNode challenge,String code,UUID device,String secret,int status) throws Exception {return postJson("/auth/verify",Map.of("challengeId",challenge.get("challengeId").asText(),"code",code,"deviceId",device,"deviceSecret",secret),null,status);}
    String login(String phone,UUID device,String secret) throws Exception {return verify(challenge(phone,device,secret),sms.codes.get(phone),device,secret,200).get("token").asText();}
    @Test void tournamentCreatorManagesTeamsFixturesAndOwnedMatchLinks() throws Exception {
        String owner=login(phone(),UUID.randomUUID(),secret()), other=login(phone(),UUID.randomUUID(),secret());
        UUID tournament=UUID.randomUUID(), a=UUID.randomUUID(), b=UUID.randomUUID(), fixture=UUID.randomUUID(), match=UUID.randomUUID();
        var input=Map.of("id",tournament,"name","District Cup","venue","Main court","startsOn","2026-10-10","halfMinutes",20,"raidSeconds",30);
        postJson("/tournaments",input,null,401);
        postJson("/tournaments",input,owner,200);
        postJson("/tournaments",input,owner,200);
        http.perform(get("/api/v1/tournaments/"+tournament).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        var others=json.readTree(http.perform(get("/api/v1/tournaments").header("Authorization","Bearer "+other)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(others.isEmpty());
        postJson("/tournaments/"+tournament+"/teams",Map.of("id",a,"name","Team 0"),other,404);
        postJson("/tournaments/"+tournament+"/teams",Map.of("id",a,"name","Team 0"),owner,200);
        postJson("/tournaments/"+tournament+"/teams",Map.of("id",a,"name","Team 0"),owner,200);
        postJson("/tournaments/"+tournament+"/teams",Map.of("id",UUID.randomUUID(),"name","team 0"),owner,409);
        postJson("/tournaments/"+tournament+"/teams",Map.of("id",b,"name","Team 1"),owner,200);
        // Rosters hold the same players the match setup uses, so the fixture link passes the roster check.
        var roster=IntStream.range(0,7).mapToObj(i->Map.of("name","Player 0"+i,"phone","+91987654320"+i)).toList();
        var rosterB=IntStream.range(0,7).mapToObj(i->Map.of("name","Player 1"+i,"phone","+91987654321"+i)).toList();
        String rosterPath="/tournaments/"+tournament+"/teams/"+a+"/roster";
        var rosterInput=Map.of("players",roster,"expectedRevision",0);
        postJson(rosterPath,rosterInput,other,404);
        var rosterSaved=postJson(rosterPath,rosterInput,owner,200);
        assertEquals(7,rosterSaved.path("teams").get(0).path("roster").size());
        var publicResponse=http.perform(get("/api/v1/public/tournaments/"+tournament))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(publicResponse.contains("phone"));
        assertFalse(publicResponse.contains("+9198765432"));
        assertTrue(publicResponse.contains("Player 00"));
        http.perform(get("/api/v1/public/tournaments").param("search",input.get("name").toString()))
            .andExpect(status().isOk());
        postJson("/tournaments/"+tournament+"/join",Map.of(),null,401);
        postJson("/tournaments/"+tournament+"/join",Map.of(),other,200);
        postJson("/tournaments/"+tournament+"/join",Map.of(),other,200);
        var joined=json.readTree(http.perform(get("/api/v1/account/joined-tournaments").header("Authorization","Bearer "+other))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1,joined.size());
        assertEquals(tournament.toString(),joined.get(0).asText());
        postJson(rosterPath,rosterInput,other,404);
        assertEquals(1,postJson(rosterPath,rosterInput,owner,200).path("teams").get(0).path("rosterRevision").asInt());
        postJson(rosterPath,Map.of("players",List.of(),"expectedRevision",0),owner,409);
        postJson("/tournaments/"+tournament+"/teams/"+b+"/roster",Map.of("players",roster,"expectedRevision",0),owner,409);
        var fixtureInput=Map.of("id",fixture,"teamAId",a,"teamBId",b,"scheduledAt","2026-10-10T10:00:00Z");
        postJson("/tournaments/"+tournament+"/fixtures",Map.of("id",UUID.randomUUID(),"teamAId",a,"teamBId",a),owner,409);
        postJson("/tournaments/"+tournament+"/fixtures",Map.of("id",UUID.randomUUID(),"teamAId",a,"teamBId",UUID.randomUUID()),owner,409);
        postJson("/tournaments/"+tournament+"/fixtures",fixtureInput,owner,200);
        postJson("/tournaments/"+tournament+"/fixtures",fixtureInput,owner,200);
        String schedulePath="/tournaments/"+tournament+"/fixtures/"+fixture+"/schedule";
        var moved=Map.of("scheduledAt","2026-10-11T10:00:00Z","expectedRevision",0);
        postJson(schedulePath,moved,other,404);
        assertEquals(1,postJson(schedulePath,moved,owner,200).path("fixtures").get(0).path("scheduleRevision").asInt());
        assertEquals(1,postJson(schedulePath,moved,owner,200).path("fixtures").get(0).path("scheduleRevision").asInt());
        postJson(schedulePath,Map.of("scheduledAt","2026-10-12T10:00:00Z","expectedRevision",0),owner,409);
        var clearSchedule=new HashMap<String,Object>();clearSchedule.put("scheduledAt",null);clearSchedule.put("expectedRevision",1);
        assertEquals(2,postJson(schedulePath,clearSchedule,owner,200).path("fixtures").get(0).path("scheduleRevision").asInt());
        // Tournament timers are only a default: a fixture's match may use other half lengths.
        var matchSetup=new HashMap<>(setup(match));matchSetup.put("halfMinutes",25);
        postJson("/matches",matchSetup,owner,201);
        UUID foreignMatch=UUID.randomUUID();postJson("/matches",setup(foreignMatch),other,201);
        String link="/tournaments/"+tournament+"/fixtures/"+fixture+"/match";
        postJson(link,Map.of("matchId",match),owner,409); // team B has no roster yet
        postJson("/tournaments/"+tournament+"/teams/"+b+"/roster",Map.of("players",rosterB,"expectedRevision",0),owner,200);
        UUID intruder=UUID.randomUUID();var intruderSetup=new HashMap<>(setup(intruder));
        var intruderTeams=new java.util.ArrayList<Object>((List<?>)intruderSetup.get("teams"));
        var outsiders=IntStream.range(0,7).mapToObj(i->Map.of("id",UUID.randomUUID(),"name",i==6?"Outsider":"Player 1"+i,"phone",i==6?"+919000000006":"+91987654321"+i)).toList();
        intruderTeams.set(1,Map.of("name","Team 1","players",outsiders));intruderSetup.put("teams",intruderTeams);
        postJson("/matches",intruderSetup,owner,201);
        assertTrue(postJson(link,Map.of("matchId",intruder),owner,409).path("message").asText().contains("Outsider"));
        UUID swapped=UUID.randomUUID();var swappedSetup=new HashMap<>(setup(swapped));
        swappedSetup.put("teams",List.of(((List<?>)swappedSetup.get("teams")).get(1),((List<?>)swappedSetup.get("teams")).get(0)));
        postJson("/matches",swappedSetup,owner,201);
        assertTrue(postJson(link,Map.of("matchId",swapped),owner,409).path("message").asText().contains("wrong way round"));
        postJson(link,Map.of("matchId",foreignMatch),owner,404);
        postJson(link,Map.of("matchId",match),other,404);
        postJson(link,Map.of("matchId",match),owner,200);
        postJson(link,Map.of("matchId",match),owner,200);
        postJson(link,Map.of("matchId",foreignMatch),owner,409);
        UUID secondFixture=UUID.randomUUID();
        postJson("/tournaments/"+tournament+"/fixtures",Map.of("id",secondFixture,"teamAId",a,"teamBId",b),owner,200);
        postJson("/tournaments/"+tournament+"/fixtures/"+secondFixture+"/match",Map.of("matchId",match),owner,409);
        postJson("/matches/"+match+"/events",Map.of("id",UUID.randomUUID(),"baseVersion",0,"rulesetVersion","raidzon-v3","occurredAt",2000,"intent",Map.of("type","TECHNICAL","side",0)),owner,200);
        var detail=json.readTree(http.perform(get("/api/v1/tournaments/"+tournament).header("Authorization","Bearer "+owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(2,detail.path("teams").size());assertEquals(2,detail.path("fixtures").size());
        assertEquals(1,detail.path("fixtures").get(0).path("scoreA").asInt());
    }
    String editProof(String token, long verifiedAt) {
        String proof=secret();
        jdbc.update("INSERT INTO auth_tokens(token_hash,account_id,device_id,expires_at,verified_at) SELECT ?,account_id,device_id,expires_at,? FROM auth_tokens WHERE token_hash=?",
            com.raidzon.identity.service.AuthService.hash(proof),verifiedAt,com.raidzon.identity.service.AuthService.hash(token));
        return proof;
    }
    @Test void profileEditRequiresFreshSameAccountProofAndCannotBeReplayed() throws Exception {
        String number=phone(), token=login(number,UUID.randomUUID(),secret());
        UUID account=jdbc.queryForObject("SELECT id FROM user_accounts WHERE phone=?",UUID.class,number);
        UUID profile=UUID.randomUUID();
        jdbc.update("INSERT INTO player_profiles(id,phone,initial_name,claimed_by) VALUES (?,?,?,?)",profile,number,"Original",account);
        postJson("/account/player-profile",Map.of("name","Changed","verificationToken",token),token,403);
        String stale=editProof(token,System.currentTimeMillis()-600_000);
        postJson("/account/player-profile",Map.of("name","Changed","verificationToken",stale),token,403);
        String foreign=login(phone(),UUID.randomUUID(),secret());
        postJson("/account/player-profile",Map.of("name","Changed","verificationToken",foreign),token,403);
        String wrongDevice=editProof(token,System.currentTimeMillis());
        jdbc.update("UPDATE auth_tokens SET device_id=(SELECT device_id FROM auth_tokens WHERE token_hash=?) WHERE token_hash=?",
            com.raidzon.identity.service.AuthService.hash(foreign),com.raidzon.identity.service.AuthService.hash(wrongDevice));
        postJson("/account/player-profile",Map.of("name","Changed","verificationToken",wrongDevice),token,403);
        UUID match=UUID.randomUUID();
        postJson("/matches",setup(match),token,201);
        String snapshot=jdbc.queryForObject("SELECT projection::text FROM matches WHERE id=?",String.class,match);
        String proof=editProof(token,System.currentTimeMillis());
        postJson("/account/player-profile",Map.of("name"," ","verificationToken",proof),token,422);
        postJson("/account/player-profile",Map.of("name","Updated Player","verificationToken",proof),token,200);
        assertEquals("Updated Player",jdbc.queryForObject("SELECT display_name FROM player_profiles WHERE id=?",String.class,profile));
        assertEquals(snapshot,jdbc.queryForObject("SELECT projection::text FROM matches WHERE id=?",String.class,match));
        postJson("/account/player-profile",Map.of("name","Replay","verificationToken",proof),token,403);
        assertEquals("Updated Player",jdbc.queryForObject("SELECT display_name FROM player_profiles WHERE id=?",String.class,profile));
    }
    @Test void teamCannotPlayTwoLiveFixtureMatchesAtOnce() throws Exception {
        String owner=login(phone(),UUID.randomUUID(),secret());
        UUID tournament=UUID.randomUUID();
        postJson("/tournaments",Map.of("id",tournament,"name","Parallel Cup","venue","Two courts","startsOn","2026-10-10","halfMinutes",20,"raidSeconds",30),owner,200);
        UUID[] teams=IntStream.range(0,4).mapToObj(t->UUID.randomUUID()).toArray(UUID[]::new);
        java.util.function.IntFunction<List<Map<String,Object>>> players=t->IntStream.range(0,7).mapToObj(i->Map.<String,Object>of("name","Player "+t+i,"phone","+9198765"+t+"000"+i)).toList();
        for(int t=0;t<4;t++){
            postJson("/tournaments/"+tournament+"/teams",Map.of("id",teams[t],"name","Team "+t),owner,200);
            postJson("/tournaments/"+tournament+"/teams/"+teams[t]+"/roster",Map.of("players",players.apply(t),"expectedRevision",0),owner,200);
        }
        // Fixture i: Team a vs Team b, scored by match i.
        int[][] pairs={{0,1},{2,3},{0,2}};
        UUID[] fixtures=new UUID[3], matches=new UUID[3];
        for(int i=0;i<3;i++){
            fixtures[i]=UUID.randomUUID();matches[i]=UUID.randomUUID();
            postJson("/tournaments/"+tournament+"/fixtures",Map.of("id",fixtures[i],"teamAId",teams[pairs[i][0]],"teamBId",teams[pairs[i][1]]),owner,200);
            var sides=Arrays.stream(pairs[i]).mapToObj(t->Map.of("name","Team "+t,"players",players.apply(t).stream().map(p->{var copy=new HashMap<String,Object>(p);copy.put("id",UUID.randomUUID());return copy;}).toList())).toList();
            postJson("/matches",Map.of("matchId",matches[i],"teams",sides,"firstTurn",0,"halfMinutes",20,"raidSeconds",30,"startedAt",1000),owner,201);
        }
        String link="/tournaments/"+tournament+"/fixtures/%s/match";
        // Different teams go live at the same time.
        postJson(link.formatted(fixtures[0]),Map.of("matchId",matches[0]),owner,200);
        postJson(link.formatted(fixtures[1]),Map.of("matchId",matches[1]),owner,200);
        postJson(link.formatted(fixtures[0]),Map.of("matchId",matches[0]),owner,200); // retry stays idempotent
        // Team 0 and Team 2 are both mid-match, so their fixture is refused.
        assertEquals("Team 0 and Team 2 are already playing a live match in this tournament. Finish that match first.",
            postJson(link.formatted(fixtures[2]),Map.of("matchId",matches[2]),owner,409).path("message").asText());
        jdbc.update("UPDATE matches SET projection=jsonb_set(projection,'{status}','\"COMPLETED\"') WHERE id=?",matches[0]);
        assertEquals("Team 2 is already playing a live match in this tournament. Finish that match first.",
            postJson(link.formatted(fixtures[2]),Map.of("matchId",matches[2]),owner,409).path("message").asText());
        jdbc.update("UPDATE matches SET projection=jsonb_set(projection,'{status}','\"COMPLETED\"') WHERE id=?",matches[1]);
        postJson(link.formatted(fixtures[2]),Map.of("matchId",matches[2]),owner,200);
    }
    Map<String,Object> setup(UUID matchId) {
        var teams=IntStream.range(0,2).mapToObj(side->Map.of("name","Team "+side,"players",IntStream.range(0,7).mapToObj(i->Map.of("id",UUID.randomUUID(),"name","Player "+side+i,"phone","+9198765432"+side+i)).toList())).toList();
        return Map.of("matchId",matchId,"teams",teams,"firstTurn",0,"halfMinutes",20,"raidSeconds",30,"startedAt",1000);
    }
    @Test void requiresVerifiedDeviceAndRevokesBearerOnLogout() throws Exception {
        http.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        String number=phone(),key=secret();UUID device=UUID.randomUUID();var challenge=challenge(number,device,key);
        assertFalse(challenge.has("code"));
        var session=verify(challenge,sms.codes.get(number),device,key,200);String token=session.get("token").asText();
        http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(challenge,sms.codes.get(number),device,key,401);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE token_hash=?",Integer.class,token));
        postJson("/auth/logout",Map.of(),token,200);
        http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void dashboardLinksVerifiedPlayerAndIsolatesAccountData() throws Exception {
        http.perform(get("/api/v1/account/dashboard")).andExpect(status().isUnauthorized());
        String scorer=login(phone(),UUID.randomUUID(),secret());
        UUID match=UUID.randomUUID();
        postJson("/matches",setup(match),scorer,201);
        // Only completed matches count on a player's profile.
        jdbc.update("UPDATE matches SET projection=jsonb_set(projection,'{status}','\"COMPLETED\"') WHERE id=?",match);
        String player=login("+919876543200",UUID.randomUUID(),secret());
        var playerResponse=json.readTree(http.perform(get("/api/v1/account/dashboard").header("Authorization","Bearer "+player))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
        assertEquals("+919876543200",playerResponse.path("phone").asText());
        assertEquals("Player 00",playerResponse.path("playerProfile").path("name").asText());
        // A new friendly no longer counts; one already on the server before V23 (legacy_stats) still does.
        assertEquals(0,playerResponse.path("playerProfile").path("matchCount").asLong());
        jdbc.update("UPDATE matches SET legacy_stats=true WHERE id=?",match);
        assertTrue(json.readTree(http.perform(get("/api/v1/account/dashboard").header("Authorization","Bearer "+player))
            .andReturn().getResponse().getContentAsString()).path("playerProfile").path("matchCount").asLong()>=1);
        assertEquals(0,playerResponse.path("playerProfile").path("raidPoints").asLong());
        assertEquals(0,playerResponse.path("playerProfile").path("tacklePoints").asLong());
        assertEquals(0,playerResponse.path("playerProfile").path("superRaids").asLong());
        assertEquals(0,playerResponse.path("playerProfile").path("superTens").asLong());
        assertEquals(0,playerResponse.path("playerProfile").path("highFives").asLong());
        assertEquals(0,playerResponse.path("playerProfile").path("superTackles").asLong());
        assertEquals(0,playerResponse.path("tournamentCount").asLong());
        assertEquals(0,playerResponse.path("teamCount").asLong());
        assertEquals(0,playerResponse.path("ownedMatchCount").asLong());
        assertTrue(playerResponse.path("recentMatches").isEmpty());
        var ownerResponse=json.readTree(http.perform(get("/api/v1/account/dashboard").header("Authorization","Bearer "+scorer))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1,ownerResponse.path("ownedMatchCount").asLong());
        assertEquals(match.toString(),ownerResponse.path("recentMatches").get(0).path("id").asText());
        assertEquals("Team 0",ownerResponse.path("recentMatches").get(0).path("teamA").asText());
        assertTrue(ownerResponse.path("playerProfile").isNull());
    }
    @Test void organizerAssignsScorerAndOnlyAcceptedDeviceCanAppend() throws Exception {
        String owner=login(phone(),UUID.randomUUID(),secret());
        String number=phone(),scorer=login(number,UUID.randomUUID(),secret());
        jdbc.update("UPDATE auth_rate_limits SET last_request=0 WHERE key LIKE 'phone:%'");
        String otherDevice=login(number,UUID.randomUUID(),secret());
        UUID match=UUID.randomUUID();
        postJson("/matches",setup(match),owner,201);
        postJson("/matches/"+match+"/scorer",Map.of("phone",number),scorer,403);
        postJson("/matches/"+match+"/scorer",Map.of("phone",number),owner,200);
        var event=Map.of("id",UUID.randomUUID(),"baseVersion",0,"rulesetVersion","raidzon-v3","occurredAt",2000,"intent",Map.of("type","TECHNICAL","side",0));
        postJson("/matches/"+match+"/events",event,owner,403);
        postJson("/matches/"+match+"/events",event,scorer,403);
        postJson("/matches/"+match+"/scorer/accept",Map.of(),owner,403);
        postJson("/matches/"+match+"/scorer/accept",Map.of(),scorer,200);
        postJson("/matches/"+match+"/scorer/accept",Map.of(),otherDevice,409);
        postJson("/matches/"+match+"/events",event,scorer,200);
        postJson("/matches/"+match+"/events",event,otherDevice,403);
        postJson("/matches/"+match+"/scorer",Map.of("phone",number),owner,409);
        assertEquals(auth.authenticate(owner).accountId(),jdbc.queryForObject("SELECT owner_account_id FROM matches WHERE id=?",UUID.class,match));
    }
    @Test void publicScorecardIsOptInReadOnlyAndExcludesPrivateFields() throws Exception {
        String owner=login(phone(),UUID.randomUUID(),secret()),other=login(phone(),UUID.randomUUID(),secret());
        UUID match=UUID.randomUUID();postJson("/matches",setup(match),owner,201);
        postJson("/matches/"+match+"/scorecard",Map.of("published",true),other,403);
        var share=postJson("/matches/"+match+"/scorecard",Map.of("published",true),owner,200).path("shareId").asText();
        String route="/api/v1/public/scorecards/"+share;
        String body=http.perform(get(route)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        var card=json.readTree(body);assertEquals("Team 0",card.path("teamA").asText());assertEquals(0,card.path("scoreA").asInt());
        assertFalse(body.contains("phone"));assertFalse(body.contains("accountId"));assertFalse(body.contains("players"));assertFalse(body.contains("+91"));
        postJson("/matches/"+match+"/events",Map.of("id",UUID.randomUUID(),"baseVersion",0,"rulesetVersion","raidzon-v3","occurredAt",2000,"intent",Map.of("type","TECHNICAL","side",0)),owner,200);
        var updated=json.readTree(http.perform(get(route)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1,updated.path("scoreA").asInt());assertEquals(1,updated.path("version").asInt());
        http.perform(post(route).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        postJson("/matches/"+match+"/scorecard",Map.of("published",false),owner,200);
        http.perform(get(route)).andExpect(status().isNotFound());
        assertEquals(share,postJson("/matches/"+match+"/scorecard",Map.of("published",true),owner,200).path("shareId").asText());
    }
    @Test void failedAttemptsCommitAndCooldownAndExpiryAreEnforced() throws Exception {
        String number=phone(),key=secret();UUID device=UUID.randomUUID();var challenge=challenge(number,device,key);
        postJson("/auth/challenges",Map.of("phone",number,"deviceId",device,"deviceSecret",key),null,429);
        String wrong=sms.codes.get(number).equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++)verify(challenge,wrong,device,key,401);
        verify(challenge,sms.codes.get(number),device,key,401);
        assertEquals(5,jdbc.queryForObject("SELECT attempts FROM auth_challenges WHERE id=?",Integer.class,UUID.fromString(challenge.get("challengeId").asText())));
        number=phone();var expired=challenge(number,device,key);
        jdbc.update("UPDATE auth_challenges SET expires_at=0 WHERE id=?",UUID.fromString(expired.get("challengeId").asText()));
        verify(expired,sms.codes.get(number),device,key,401);
    }
    @Test void anotherPhysicalDeviceCannotReuseAnExistingDeviceId() throws Exception {
        UUID device=UUID.randomUUID();login(phone(),device,secret());
        String number=phone(),otherSecret=secret();var attempt=challenge(number,device,otherSecret);
        verify(attempt,sms.codes.get(number),device,otherSecret,401);
    }
    @Test void expiredBearerCannotReadIdentity() throws Exception {
        String token=login(phone(),UUID.randomUUID(),secret());
        jdbc.update("UPDATE auth_tokens SET expires_at=0 WHERE token_hash=?",com.raidzon.identity.service.AuthService.hash(token));
        http.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void claimAndOrderedEventRetryEnforceOwnershipAndPreserveProfiles() throws Exception {
        UUID device=UUID.randomUUID(),match=UUID.randomUUID();String token=login(phone(),device,secret());var setup=setup(match);
        postJson("/matches",setup,null,401);postJson("/matches",setup,token,201);postJson("/matches",setup,token,200);
        String other=login(phone(),UUID.randomUUID(),secret());postJson("/matches",setup,other,403);
        var event=Map.of("id",UUID.randomUUID(),"baseVersion",0,"rulesetVersion","raidzon-v3","occurredAt",2000,"intent",Map.of("type","TECHNICAL","side",0));
        var ack=postJson("/matches/"+match+"/events",event,token,200);
        assertEquals(1,ack.get("acceptedVersion").asInt());assertEquals(1,ack.get("state").get("scores").get(0).asInt());
        assertTrue(postJson("/matches/"+match+"/events",event,token,200).get("duplicate").asBoolean());
        postJson("/matches/"+match+"/events",Map.of("id",UUID.randomUUID(),"baseVersion",0,"rulesetVersion","raidzon-v3","occurredAt",3000,"intent",Map.of("type","TECHNICAL","side",1)),token,409);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM match_events WHERE match_id=?",Integer.class,match));
        var second=new HashMap<>(setup(UUID.randomUUID()));postJson("/matches",second,token,201);
        assertEquals(14,jdbc.queryForObject("SELECT count(*) FROM player_profiles WHERE phone LIKE '+9198765432%'",Integer.class));
        var invalid=new HashMap<>(setup(UUID.randomUUID()));invalid.put("firstTurn","0");postJson("/matches",invalid,token,422);
        http.perform(get("/api/v1/unknown").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
    @Test void appStatsAreAdminOnly() throws Exception {
        String admin=login(ADMIN_PHONE,UUID.randomUUID(),secret()), other=login(phone(),UUID.randomUUID(),secret());
        http.perform(get("/api/v1/admin/stats")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/admin/stats").header("Authorization","Bearer "+other)).andExpect(status().isForbidden());
        var stats=json.readTree(http.perform(get("/api/v1/admin/stats").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(stats.path("totalUsers").asLong()>=2);
        assertTrue(stats.path("activeToday").asLong()>=2); // both accounts were just seen
        assertTrue(stats.path("newUsers7Days").asLong()>=2);
        for(String field:List.of("active7Days","active30Days","totalInstalls","installs7Days","matchesScored","matchesScored7Days"))assertTrue(stats.has(field),field);
    }
    @Test void installPingIsPublicAndIdempotent() throws Exception {
        String id="inst_"+UUID.randomUUID().toString().replace("-","");
        var input=Map.of("installId",id,"source","appinstalled");
        assertTrue(postJson("/public/installs",input,null,200).path("recorded").asBoolean());
        assertFalse(postJson("/public/installs",Map.of("installId",id,"source","standalone_launch"),null,200).path("recorded").asBoolean());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app_installs WHERE install_id=?",Integer.class,id));
        assertNull(jdbc.queryForObject("SELECT user_id FROM app_installs WHERE install_id=?",UUID.class,id));
        String token=login(phone(),UUID.randomUUID(),secret()), linked="inst_"+UUID.randomUUID().toString().replace("-","");
        postJson("/public/installs",Map.of("installId",linked,"source","standalone_launch"),token,200);
        assertNotNull(jdbc.queryForObject("SELECT user_id FROM app_installs WHERE install_id=?",UUID.class,linked));
        // A bad session still counts the install, anonymously.
        String anon="inst_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(postJson("/public/installs",Map.of("installId",anon,"source","appinstalled"),"x".repeat(43),200).path("recorded").asBoolean());
        postJson("/public/installs",Map.of("installId",id,"source","other"),null,422);
        postJson("/public/installs",Map.of("installId","short","source","appinstalled"),null,422);
    }
    @Test void lastSeenIsWrittenAtMostHourly() throws Exception {
        String token=login(phone(),UUID.randomUUID(),secret());
        UUID account=auth.authenticate(token).accountId();
        assertNotNull(jdbc.queryForObject("SELECT last_seen_at FROM user_accounts WHERE id=?",java.sql.Timestamp.class,account));
        jdbc.update("UPDATE user_accounts SET last_seen_at=now()-interval '30 minutes' WHERE id=?",account);
        var recent=jdbc.queryForObject("SELECT last_seen_at FROM user_accounts WHERE id=?",java.sql.Timestamp.class,account);
        auth.forgetVerifiedTokens();auth.authenticate(token);
        assertEquals(recent,jdbc.queryForObject("SELECT last_seen_at FROM user_accounts WHERE id=?",java.sql.Timestamp.class,account));
        jdbc.update("UPDATE user_accounts SET last_seen_at=now()-interval '2 hours' WHERE id=?",account);
        auth.forgetVerifiedTokens();auth.authenticate(token);
        assertTrue(jdbc.queryForObject("SELECT last_seen_at > now()-interval '1 minute' FROM user_accounts WHERE id=?",Boolean.class,account));
    }
}
