package com.raidzon.tournament.controller;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.tournament.dto.JoinRequest;
import com.raidzon.tournament.dto.Tournament;
import com.raidzon.tournament.service.JoinRequestService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Team join requests. All routes require sign-in. */
@RestController @Profile("postgres") @RequestMapping("/api/v1")
public class JoinRequestController {
    private final JoinRequestService requests;
    public JoinRequestController(JoinRequestService requests) { this.requests = requests; }

    @PostMapping("/tournaments/{id}/join-requests")
    public JoinRequest request(@PathVariable UUID id, @RequestBody JoinRequest.CreateInput input, @RequestAttribute("identity") AuthIdentity actor) {
        return requests.request(id, input, actor.accountId());
    }
    @GetMapping("/tournaments/{id}/join-requests")
    public List<JoinRequest> forTournament(@PathVariable UUID id, @RequestAttribute("identity") AuthIdentity actor) {
        return requests.forTournament(id, actor.accountId());
    }
    @PostMapping("/tournaments/{id}/registration")
    public Tournament.Detail registration(@PathVariable UUID id, @RequestBody JoinRequest.RegistrationInput input, @RequestAttribute("identity") AuthIdentity actor) {
        return requests.registration(id, input.open(), actor.accountId());
    }
    @GetMapping("/teams/{teamId}/join-requests")
    public List<JoinRequest> forTeam(@PathVariable UUID teamId, @RequestAttribute("identity") AuthIdentity actor) {
        return requests.forTeam(teamId, actor.accountId());
    }
    @PostMapping("/join-requests/{requestId}/withdraw")
    public JoinRequest withdraw(@PathVariable UUID requestId, @RequestAttribute("identity") AuthIdentity actor) {
        return requests.withdraw(requestId, actor.accountId());
    }
    @PostMapping("/join-requests/{requestId}/approve")
    public Tournament.Detail approve(@PathVariable UUID requestId, @RequestBody(required = false) JoinRequest.Decision decision, @RequestAttribute("identity") AuthIdentity actor) {
        return requests.approve(requestId, decision, actor.accountId());
    }
    @PostMapping("/join-requests/{requestId}/reject")
    public JoinRequest reject(@PathVariable UUID requestId, @RequestBody(required = false) JoinRequest.Decision decision, @RequestAttribute("identity") AuthIdentity actor) {
        return requests.reject(requestId, decision, actor.accountId());
    }
}
