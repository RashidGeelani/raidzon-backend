package com.raidzon.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** A saved team asks to join another organizer's tournament. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("postgres") @Import(AuthHttpTest.SmsConfig.class)
@EnabledIfEnvironmentVariable(named="RAIDZON_TEST_DATABASE_URL",matches=".+")
class JoinRequestHttpTest {
    @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired AuthHttpTest.TestSms sms;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("RAIDZON_TEST_DATABASE_URL"), user=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_USERNAME","raidzon_test"), password=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD","");
        var data=new PGSimpleDataSource();data.setURL(url);data.setUser(user);data.setPassword(password);
        String schema="test_join_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(data).execute("CREATE SCHEMA "+schema);
        properties.add("raidzon.database.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+schema);
        properties.add("raidzon.database.username",()->user);properties.add("raidzon.database.password",()->password);
        properties.add("raidzon.auth.pepper",()->"test-only-pepper-that-is-at-least-32-characters");
    }
    String phone(){return "+91"+String.format(Locale.ROOT,"%010d",Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),10_000_000_000L));}
    String login(String phone) throws Exception {
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);UUID device=UUID.randomUUID();
        var challenge=send("/auth/challenges",Map.of("phone",phone,"deviceId",device,"deviceSecret",secret),null,200);
        return send("/auth/verify",Map.of("challengeId",challenge.get("challengeId").asText(),"code",sms.codes.get(phone),"deviceId",device,"deviceSecret",secret),null,200).get("token").asText();
    }
    JsonNode send(String path,Object body,String token,int status) throws Exception {
        var request=post("/api/v1"+path).contentType("application/json").content(json.writeValueAsBytes(body));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return json.readTree(http.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    JsonNode read(String path,String token,int status) throws Exception {
        return json.readTree(http.perform(get("/api/v1"+path).header("Authorization","Bearer "+token)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    UUID team(String owner) throws Exception {
        UUID id=UUID.randomUUID();send("/teams",Map.of("id",id,"name","Valley Raiders"),owner,200);return id;
    }
    JsonNode addPlayer(UUID team,String typed,String phone,String owner) throws Exception {
        return send("/teams/"+team+"/members",Map.of("id",UUID.randomUUID(),"name",typed,"phone",phone),owner,200);
    }
    static JsonNode find(JsonNode list,String field,String value) {
        return StreamSupport.stream(list.spliterator(),false).filter(n->n.path(field).asText().equals(value)).findFirst().orElse(null);
    }
    void ownName(String phone,String name){jdbc.update("UPDATE player_profiles SET display_name=? WHERE phone=?",name,phone);}

    @Test void thirteenPlayerTeamRequestsWithMessage() throws Exception {
        String organizer=login(phone()), owner=login(phone());
        UUID tournament=UUID.randomUUID();
        send("/tournaments",Map.of("id",tournament,"name","Memorial Championship","venue","Bandipora","startsOn","2026-12-10","halfMinutes",20,"raidSeconds",30),organizer,200);
        // The tournament already has a team with a roster, so the player-clash check runs.
        UUID other=UUID.randomUUID();
        send("/tournaments/"+tournament+"/teams",Map.of("id",other,"name","Other Club"),organizer,200);
        var roster=new ArrayList<Map<String,Object>>();
        for(int i=0;i<7;i++)roster.add(Map.of("name","Other "+i,"phone",phone(),"jersey",i+1));
        send("/tournaments/"+tournament+"/teams/"+other+"/roster",Map.of("players",roster,"expectedRevision",0),organizer,200);
        UUID team=team(owner);
        for(int i=0;i<13;i++)addPlayer(team,"Player "+i,phone(),owner);
        var created=send("/tournaments/"+tournament+"/join-requests",Map.of("id",UUID.randomUUID(),"teamId",team,"message","Please let us in, we are ready."),owner,200);
        assertEquals("PENDING",created.get("status").asText());
        assertEquals(13,created.get("squadSize").asInt());
        assertEquals(13,created.get("players").size());
        assertEquals(1,read("/tournaments/"+tournament+"/join-requests",organizer,200).size());
        assertEquals(1,read("/teams/"+team+"/join-requests",owner,200).size());
        send("/join-requests/"+created.get("id").asText()+"/approve",Map.of(),organizer,200);
    }
}
