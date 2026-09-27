package com.raidzon.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres") @Import({AuthHttpTest.SmsConfig.class,OrganizerScorerJourneyTest.BlockingClientConfig.class})
@EnabledIfEnvironmentVariable(named="RAIDZON_TEST_DATABASE_URL",matches=".+")
class OrganizerScorerJourneyTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class BlockingClientConfig {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory> journeyConnector() {
            return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
        }
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.client.RestTemplateBuilder restTemplateBuilder() {
            return new org.springframework.boot.web.client.RestTemplateBuilder()
                .requestFactory(org.springframework.http.client.SimpleClientHttpRequestFactory::new);
        }
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired AuthHttpTest.TestSms sms;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){
        String url=System.getenv("RAIDZON_TEST_DATABASE_URL"),user=System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_USERNAME","raidzon_test");
        var data=new PGSimpleDataSource();data.setURL(url);data.setUser(user);data.setPassword(System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD",""));
        String schema="journey_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(data).execute("CREATE SCHEMA "+schema);
        properties.add("raidzon.database.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+schema);
        properties.add("raidzon.database.username",()->user);
        properties.add("raidzon.database.password",()->System.getenv().getOrDefault("RAIDZON_TEST_DATABASE_PASSWORD",""));
        properties.add("raidzon.auth.pepper",()->"journey-test-only-pepper-at-least-32-characters");
    }
    JsonNode request(String path,Object body,String token,int expected) throws Exception {
        var connection=(HttpURLConnection)URI.create("http://127.0.0.1:"+port+"/api/v1"+path).toURL().openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(10000);
        try {
            if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
            if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
                try(var output=connection.getOutputStream()){output.write(json.writeValueAsBytes(body));}}
            int status=connection.getResponseCode();assertEquals(expected,status,path);
            try(var input=status>=400?connection.getErrorStream():connection.getInputStream()){return json.readTree(input);}
        } finally {connection.disconnect();}
    }
    String login(String phone,UUID device) throws Exception {
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var challenge=request("/auth/challenges",Map.of("phone",phone,"deviceId",device,"deviceSecret",secret),null,200);
        return request("/auth/verify",Map.of("challengeId",challenge.path("challengeId").asText(),"code",sms.codes.get(phone),"deviceId",device,"deviceSecret",secret),null,200).path("token").asText();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"raidzon-v2","raidzon-v3"})
    void realHttpOrganizerScorerSpectatorJourney(String ruleset) throws Exception {
        String ownerPhone=ruleset.equals("raidzon-v2") ? "+919876543210" : "+919876543212";
        String scorerPhone=ruleset.equals("raidzon-v2") ? "+919876543211" : "+919876543213";
        String owner=login(ownerPhone,UUID.randomUUID()),scorer=login(scorerPhone,UUID.randomUUID());
        UUID match=UUID.randomUUID();
        var teams=IntStream.range(0,2).mapToObj(side->Map.of("name","Team "+side,"players",IntStream.range(0,7).mapToObj(i->Map.of("id",UUID.randomUUID(),"name","Player "+side+i,"phone","+9198765432"+side+i)).toList())).toList();
        request("/matches",Map.of("matchId",match,"teams",teams,"firstTurn",0,"halfMinutes",20,"raidSeconds",30,"startedAt",1000,"rulesetVersion",ruleset),owner,201);
        String share=request("/matches/"+match+"/scorecard",Map.of("published",true),owner,200).path("shareId").asText();
        request("/matches/"+match+"/scorer",Map.of("phone",scorerPhone),owner,200);
        assertEquals(match.toString(),request("/account/assignments",null,scorer,200).get(0).path("matchId").asText());
        assertEquals(ruleset,request("/account/assignments",null,scorer,200).get(0).path("rulesetVersion").asText());
        request("/matches/"+match+"/scorer/accept",Map.of(),scorer,200);
        var event=Map.of("id",UUID.randomUUID(),"baseVersion",0,"rulesetVersion",ruleset,"occurredAt",2000,"intent",Map.of("type","TECHNICAL","side",0));
        // A queued, not-yet-uploaded event cannot change the spectator score.
        assertEquals(0,request("/public/scorecards/"+share,null,null,200).path("scoreA").asInt());
        request("/matches/"+match+"/events",event,owner,403);
        request("/matches/"+match+"/events",event,scorer,200);
        assertTrue(request("/matches/"+match+"/events",event,scorer,200).path("duplicate").asBoolean());
        var publicCard=request("/public/scorecards/"+share,null,null,200);
        assertEquals(1,publicCard.path("scoreA").asInt());assertFalse(publicCard.toString().contains("phone"));
        request("/matches/"+match+"/scorecard",Map.of("published",false),owner,200);
        request("/public/scorecards/"+share,null,null,404);
        request("/auth/logout",Map.of(),scorer,200);
        request("/account/assignments",null,scorer,401);
    }
}
