package com.raidzon.team.controller;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.team.dto.Team;
import com.raidzon.team.service.TeamService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Reusable teams. All routes require sign-in (AuthFilter) and return the updated team. */
@RestController @Profile("postgres") @RequestMapping("/api/v1/teams")
public class TeamController {
    private final TeamService teams;
    public TeamController(TeamService teams) { this.teams = teams; }

    @GetMapping
    public List<Team.Summary> mine(@RequestAttribute("identity") AuthIdentity actor) {
        return teams.mine(actor.accountId());
    }
    @PostMapping
    public Team.Detail create(@RequestBody Team.CreateInput input, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.create(input, actor.accountId());
    }
    @GetMapping("/{id}")
    public Team.Detail detail(@PathVariable UUID id, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.detail(id, actor.accountId());
    }
    @PostMapping("/{id}/details")
    public Team.Detail details(@PathVariable UUID id, @RequestBody Team.DetailsInput input, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.details(id, input, actor.accountId());
    }
    @PostMapping("/{id}/archive")
    public Team.Detail archive(@PathVariable UUID id, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.archive(id, actor.accountId());
    }
    @PostMapping("/{id}/members")
    public Team.Detail addMember(@PathVariable UUID id, @RequestBody Team.MemberInput input, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.addMember(id, input, actor.accountId());
    }
    @PostMapping("/{id}/members/{member}")
    public Team.Detail editMember(@PathVariable UUID id, @PathVariable UUID member, @RequestBody Team.MemberEdit input, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.editMember(id, member, input, actor.accountId());
    }
    @PostMapping("/{id}/members/{member}/remove")
    public Team.Detail removeMember(@PathVariable UUID id, @PathVariable UUID member, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.removeMember(id, member, actor.accountId());
    }
    @PostMapping("/{id}/leadership")
    public Team.Detail leadership(@PathVariable UUID id, @RequestBody Team.LeadershipInput input, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.leadership(id, input, actor.accountId());
    }
    @PostMapping("/{id}/staff")
    public Team.Detail addStaff(@PathVariable UUID id, @RequestBody Team.StaffInput input, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.addStaff(id, input, actor.accountId());
    }
    @PostMapping("/{id}/staff/remove")
    public Team.Detail removeStaff(@PathVariable UUID id, @RequestBody Team.StaffRemoval input, @RequestAttribute("identity") AuthIdentity actor) {
        return teams.removeStaff(id, input, actor.accountId());
    }
}
