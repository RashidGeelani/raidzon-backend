package com.raidzon.scorecard.controller;
import com.raidzon.scorecard.dto.PublicScorecard;
import com.raidzon.scorecard.service.ScorecardService;
import com.raidzon.identity.dto.AuthIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @Profile("postgres") @RequestMapping("/api/v1")
public class ScorecardController {
    private final ScorecardService scorecards;
    public ScorecardController(ScorecardService scorecards){this.scorecards=scorecards;}
    public record PublishInput(boolean published) {}
    public record ShareLink(UUID shareId,boolean published) {}
    @PostMapping("/matches/{id}/scorecard") public ShareLink publish(@PathVariable UUID id,@RequestBody PublishInput input,@RequestAttribute("identity") AuthIdentity actor){
        return new ShareLink(scorecards.publish(id,actor,input.published()),input.published());
    }
    @GetMapping("/public/scorecards/{shareId}") public PublicScorecard read(@PathVariable UUID shareId){return scorecards.read(shareId);}
}
