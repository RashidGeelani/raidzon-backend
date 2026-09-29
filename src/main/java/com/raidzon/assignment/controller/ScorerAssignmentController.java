package com.raidzon.assignment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.raidzon.assignment.service.ScorerAssignmentService;
import com.raidzon.identity.dto.AuthIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @Profile("postgres") @ConditionalOnProperty(name="raidzon.officials.enabled", havingValue="true") @RequestMapping("/api/v1")
public class ScorerAssignmentController {
    private final ScorerAssignmentService assignments;
    public ScorerAssignmentController(ScorerAssignmentService assignments){this.assignments=assignments;}
    public record AssignInput(String phone) {}
    @GetMapping("/account/assignments") public List<com.raidzon.assignment.dto.Assignment> list(@RequestAttribute("identity") AuthIdentity actor){return assignments.list(actor);}
    @PostMapping("/matches/{id}/scorer") public Map<String,Boolean> assign(@PathVariable UUID id,@RequestBody AssignInput input,@RequestAttribute("identity") AuthIdentity actor){assignments.assign(id,input.phone(),actor);return Map.of("assigned",true);}
    @PostMapping("/matches/{id}/scorer/accept") public JsonNode accept(@PathVariable UUID id,@RequestAttribute("identity") AuthIdentity actor){return assignments.accept(id,actor);}
}
