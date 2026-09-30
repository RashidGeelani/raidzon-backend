package com.raidzon.tournament.controller;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.tournament.dto.Tournament;
import com.raidzon.tournament.service.TournamentService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController @Profile("postgres") @RequestMapping("/api/v1")
public class TournamentBrowseController {
    private final TournamentService tournaments;
    public TournamentBrowseController(TournamentService tournaments){this.tournaments=tournaments;}
    @GetMapping("/public/tournaments")
    public List<Tournament> browse(@RequestParam(defaultValue="") String search){return tournaments.browse(search);}
    @GetMapping("/public/leaderboards")
    public List<Tournament.PlayerRanking> leaderboard(@RequestParam(required=false) UUID tournamentId,@RequestParam(defaultValue="raid") String category){return tournaments.leaderboard(tournamentId,category);}
    @GetMapping("/public/tournaments/{id}")
    public Tournament.PublicDetail detail(@PathVariable UUID id){return tournaments.publicDetail(id);}
    @GetMapping("/account/joined-tournaments")
    public List<UUID> joined(@RequestAttribute("identity") AuthIdentity actor){return tournaments.joined(actor.accountId());}
    @PostMapping("/tournaments/{id}/join")
    public Map<String,Boolean> join(@PathVariable UUID id,@RequestAttribute("identity") AuthIdentity actor){
        tournaments.join(id,actor.accountId());return Map.of("joined",true);
    }
    @PostMapping("/tournaments/{id}/leave")
    public Map<String,Boolean> leave(@PathVariable UUID id,@RequestAttribute("identity") AuthIdentity actor){
        tournaments.leave(id,actor.accountId());return Map.of("joined",false);
    }
}
