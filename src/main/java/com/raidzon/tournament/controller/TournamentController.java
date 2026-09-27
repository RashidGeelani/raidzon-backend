package com.raidzon.tournament.controller;

import com.raidzon.tournament.dto.Tournament;
import com.raidzon.tournament.service.TournamentService;
import com.raidzon.identity.dto.AuthIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @Profile("postgres") @RequestMapping("/api/v1/tournaments")
public class TournamentController {
    private final TournamentService tournaments;
    public TournamentController(TournamentService tournaments){this.tournaments=tournaments;}
    @GetMapping public List<Tournament> list(@RequestAttribute("identity")AuthIdentity actor){return tournaments.list(actor.accountId());}
    @GetMapping("/{id}") public Tournament.Detail detail(@PathVariable UUID id,@RequestAttribute("identity")AuthIdentity actor){return tournaments.detail(id,actor.accountId());}
    @PostMapping public Tournament.Detail create(@RequestBody Tournament input,@RequestAttribute("identity")AuthIdentity actor){return tournaments.create(input,actor.accountId());}
    @PostMapping("/{id}/teams") public Tournament.Detail team(@PathVariable UUID id,@RequestBody Tournament.TeamInput input,@RequestAttribute("identity")AuthIdentity actor){return tournaments.team(id,input,actor.accountId());}
    @PostMapping("/{id}/teams/{team}/roster") public Tournament.Detail roster(@PathVariable UUID id,@PathVariable UUID team,@RequestBody Tournament.RosterInput input,@RequestAttribute("identity")AuthIdentity actor){return tournaments.roster(id,team,input,actor.accountId());}
    @PostMapping("/{id}/fixtures") public Tournament.Detail fixture(@PathVariable UUID id,@RequestBody Tournament.FixtureInput input,@RequestAttribute("identity")AuthIdentity actor){return tournaments.fixture(id,input,actor.accountId());}
    @PostMapping("/{id}/fixtures/{fixture}/match") public Tournament.Detail link(@PathVariable UUID id,@PathVariable UUID fixture,@RequestBody Tournament.LinkInput input,@RequestAttribute("identity")AuthIdentity actor){return tournaments.link(id,fixture,input,actor.accountId());}
    @PostMapping("/{id}/fixtures/{fixture}/schedule") public Tournament.Detail reschedule(@PathVariable UUID id,@PathVariable UUID fixture,@RequestBody Tournament.ScheduleInput input,@RequestAttribute("identity")AuthIdentity actor){return tournaments.reschedule(id,fixture,input,actor.accountId());}
}
