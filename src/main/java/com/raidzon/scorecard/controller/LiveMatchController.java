package com.raidzon.scorecard.controller;

import com.raidzon.scorecard.dto.LiveMatchView;
import com.raidzon.scorecard.repository.LiveMatchRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @Profile("postgres") @RequestMapping("/api/v1/public/matches")
public class LiveMatchController {
    private final LiveMatchRepository matches;
    public LiveMatchController(LiveMatchRepository matches){this.matches=matches;}
    @GetMapping("/{id}") public LiveMatchView read(@PathVariable UUID id){return matches.read(id);}
}
