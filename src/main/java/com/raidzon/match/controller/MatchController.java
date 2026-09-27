package com.raidzon.match.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.match.domain.MatchState;
import com.raidzon.match.dto.*;
import com.raidzon.match.service.GuestClaimService;
import com.raidzon.match.service.MatchService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

@RestController @Profile("postgres") @RequestMapping("/api/v1/matches")
public class MatchController {
    private final MatchService matches; private final GuestClaimService claims; private final ObjectMapper json; private final Clock clock;
    public MatchController(MatchService matches,GuestClaimService claims,ObjectMapper json,Clock clock){this.matches=matches;this.claims=claims;this.json=json;this.clock=clock;}
    private <T> T decode(JsonNode input,Class<T> type,String... fields){
        if(!input.isObject())throw new IllegalArgumentException("Expected a JSON object.");
        var allowed=Set.of(fields);
        for(String field:fields)if(!input.hasNonNull(field))throw new IllegalArgumentException("Missing field: "+field);
        input.fieldNames().forEachRemaining(field->{if(!allowed.contains(field))throw new IllegalArgumentException("Unexpected field: "+field);});
        for(String field: new String[]{"firstTurn","halfMinutes","raidSeconds","startedAt","baseVersion","occurredAt"})
            if(input.has(field) && !input.get(field).isIntegralNumber())throw new IllegalArgumentException("Expected integer field: "+field);
        try{return json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .treeToValue(input,type);}catch(Exception error){throw new IllegalArgumentException("Invalid request fields.");}
    }
    private void time(long value){if(value>clock.millis()+300_000)throw new IllegalArgumentException("Device time is ahead of the server. Correct the device clock before syncing.");}
    private JsonNode state(MatchState state){
        ObjectNode result=json.valueToTree(state);
        if(state.winner()==MatchState.Winner.TEAM_A)result.put("winner",0);
        if(state.winner()==MatchState.Winner.TEAM_B)result.put("winner",1);
        return result;
    }
    @PostMapping public ResponseEntity<JsonNode> claim(@RequestBody JsonNode body,@RequestAttribute("identity") AuthIdentity actor){
        if (body.isObject() && !body.has("rulesetVersion")) {
            body = body.deepCopy();
            ((ObjectNode) body).put("rulesetVersion", com.raidzon.match.domain.MatchEngine.RULESET_VERSION);
        }
        var request=decode(body,CreateMatchRequest.class,"matchId","teams","firstTurn","halfMinutes","raidSeconds","startedAt","rulesetVersion");time(request.startedAt());
        var result=claims.claim(request,actor);ObjectNode response=json.valueToTree(result);response.set("state",state(result.state()));
        return ResponseEntity.status(result.duplicate()?200:201).body(response);
    }
    @PostMapping("/{matchId}/events") public JsonNode event(@PathVariable UUID matchId,@RequestBody JsonNode body,@RequestAttribute("identity") AuthIdentity actor){
        var request=decode(body,MatchEventRequest.class,"id","baseVersion","rulesetVersion","occurredAt","intent");time(request.occurredAt());
        var result=matches.append(matchId,request,actor.accountId(),actor.deviceId());ObjectNode response=json.valueToTree(result);response.set("state",state(result.state()));return response;
    }
}
