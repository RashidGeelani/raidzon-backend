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

/** Adding a phone to a team is an invitation: no identity leaks before acceptance, no rights for invited staff. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("postgres") @Import(AuthHttpTest.SmsConfig.class)
@EnabledIfEnvironmentVariable(named="RAIDZON_TEST_DATABASE_URL",matches=".+")
class TeamInvitationHttpTest {
    @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired AuthHttpTest.TestSms sms;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=System.getenv("RAIDZON_TEST_DATABASE_URL"), user=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_USERNAME","raidzon_test"), password=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD","");
        var data=new PGSimpleDataSource();data.setURL(url);data.setUser(user);data.setPassword(password);
        String schema="test_team_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(data).execute("CREATE SCHEMA "+schema);
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

    @Test void unrelatedOwnerSeesOnlyTypedNameUntilAcceptThenPlayerIsViewOnly() throws Exception {
        String owner=login(phone()), playerPhone=phone(), player=login(playerPhone), otherPhone=phone();
        UUID team=team(owner);
        addPlayer(team,"Typed Name",playerPhone,owner);
        ownName(playerPhone,"Real Name");
        var detail=addPlayer(team,"Someone",otherPhone,owner);
        var invited=find(detail.get("members"),"squadName","Typed Name");
        assertEquals("Typed Name",invited.get("name").asText());
        assertFalse(invited.get("claimed").asBoolean());
        assertTrue(invited.get("invited").asBoolean());
        assertFalse(invited.has("profileId"));
        assertFalse(json.writeValueAsString(detail).contains("Real Name"));
        assertEquals(2,detail.get("members").size()); // invited players are in the squad and can be picked for matches

        // Before accepting, the player has no access but sees the invitation and a notification.
        read("/teams/"+team,player,404);
        assertNull(find(read("/teams",player,200),"id",team.toString()));
        var invitation=read("/teams/invitations",player,200).get(0);
        assertEquals(team.toString(),invitation.get("teamId").asText());
        assertEquals("PLAYER",invitation.get("role").asText());
        assertNotNull(find(read("/account/notifications",player,200).get("items"),"kind","TEAM_INVITE"));

        var accepted=send("/teams/"+team+"/invitations/accept",Map.of(),player,200);
        assertEquals("PLAYER",accepted.get("myRole").asText());
        for(var member:accepted.get("members"))assertTrue(member.get("phone").isNull(),"players never see phones");
        send("/teams/"+team+"/invitations/accept",Map.of(),player,200); // retry is a no-op
        assertEquals(0,read("/teams/invitations",player,200).size());

        // View-only: no squad changes.
        send("/teams/"+team+"/members",Map.of("id",UUID.randomUUID(),"name","X","phone",phone()),player,403);
        send("/teams/"+team+"/details",Map.of("name","Hijacked"),player,403);

        var mine=read("/teams/"+team,owner,200);
        var verified=find(mine.get("members"),"squadName","Typed Name");
        assertEquals("Real Name",verified.get("name").asText());
        assertTrue(verified.get("claimed").asBoolean());
        assertFalse(verified.get("invited").asBoolean());
        assertEquals(playerPhone,verified.get("phone").asText());
        assertEquals("PLAYER",find(read("/teams",player,200),"id",team.toString()).get("myRole").asText());

        send("/teams/"+team+"/leave",Map.of(),player,200);
        send("/teams/"+team+"/leave",Map.of(),player,200);
        read("/teams/"+team,player,404);
        assertEquals(1,read("/teams/"+team,owner,200).get("members").size());
        send("/teams/"+team+"/leave",Map.of(),owner,403);
    }

    @Test void invitedStaffHaveNoRightsAndDeclineTellsTheOwner() throws Exception {
        String owner=login(phone()), staffPhone=phone(), staff=login(staffPhone);
        UUID team=team(owner);
        var detail=send("/teams/"+team+"/staff",Map.of("name","Typed Coach","phone",staffPhone,"role","MANAGER"),owner,200);
        ownName(staffPhone,"Real Manager");
        detail=read("/teams/"+team,owner,200);
        var invited=detail.get("staff").get(0);
        assertEquals("Typed Coach",invited.get("name").asText());
        assertTrue(invited.get("invited").asBoolean());
        assertFalse(invited.has("profileId"));
        assertTrue(invited.hasNonNull("id"));
        assertFalse(json.writeValueAsString(detail).contains("Real Manager"));

        // An invited manager cannot see or edit the squad, or register the team.
        read("/teams/"+team,staff,404);
        send("/teams/"+team+"/members",Map.of("id",UUID.randomUUID(),"name","X","phone",phone()),staff,404);
        assertNull(find(read("/teams",staff,200),"id",team.toString()));

        addPlayer(team,"Typed Player",staffPhone,owner);
        assertEquals(2,read("/teams/invitations",staff,200).size());
        assertEquals(0,send("/teams/"+team+"/invitations/decline",Map.of(),staff,200).size());
        send("/teams/"+team+"/invitations/decline",Map.of(),staff,200);
        var after=read("/teams/"+team,owner,200);
        assertEquals(0,after.get("staff").size());
        assertEquals(0,after.get("members").size());
        assertNotNull(find(read("/account/notifications",owner,200).get("items"),"kind","TEAM_INVITE_DECLINED"));
        send("/teams/"+team+"/invitations/accept",Map.of(),staff,404);

        // Accepted staff get their rights; removal uses the opaque staff ID.
        var again=send("/teams/"+team+"/staff",Map.of("name","Typed Coach","phone",staffPhone,"role","MANAGER"),owner,200);
        assertEquals("MANAGER",send("/teams/"+team+"/invitations/accept",Map.of(),staff,200).get("myRole").asText());
        addPlayer(team,"Added By Manager",phone(),staff);
        assertEquals("Real Manager",read("/teams/"+team,owner,200).get("staff").get(0).get("name").asText());
        send("/teams/"+team+"/staff/remove",Map.of("id",again.get("staff").get(0).get("id").asText()),owner,200);
        read("/teams/"+team,staff,404);
    }

    @Test void addingYourOwnNumberNeedsNoInvitation() throws Exception {
        String ownerPhone=phone(), owner=login(ownerPhone);
        UUID team=team(owner);
        var me=addPlayer(team,"Me",ownerPhone,owner).get("members").get(0);
        assertFalse(me.get("invited").asBoolean());
        assertTrue(me.get("claimed").asBoolean());
    }
}
