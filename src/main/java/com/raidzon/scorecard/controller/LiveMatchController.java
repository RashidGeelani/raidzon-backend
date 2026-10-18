package com.raidzon.scorecard.controller;

import com.raidzon.scorecard.dto.LiveMatchView;
import com.raidzon.scorecard.live.LiveViewCache;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @Profile("postgres") @RequestMapping("/api/v1/public/matches")
public class LiveMatchController {
    private final LiveViewCache matches;
    public LiveMatchController(LiveViewCache matches){this.matches=matches;}
    @GetMapping("/{id}") public LiveMatchView read(@PathVariable UUID id){return matches.read(id);}
}
