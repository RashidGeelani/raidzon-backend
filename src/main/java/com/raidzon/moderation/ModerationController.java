package com.raidzon.moderation;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.scorecard.live.LiveMatchHub;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** "Report this match" for signed-in viewers, deleting a match by its organizer, and the admin review queue. All routes require sign-in. */
@RestController @Profile("postgres") @RequestMapping("/api/v1")
public class ModerationController {
    private final ModerationRepository moderation;
    private final AdminAccess admins;
    private final LiveMatchHub live;
    public ModerationController(ModerationRepository moderation, AdminAccess admins, LiveMatchHub live) {
        this.moderation = moderation; this.admins = admins; this.live = live;
    }

    @PostMapping("/matches/{matchId}/reports")
    public Moderation.ReportResult report(@PathVariable UUID matchId, @RequestBody Moderation.ReportInput input,
                                          @RequestAttribute("identity") AuthIdentity actor) {
        if (input == null || !Moderation.REASONS.contains(input.reason())) throw new IllegalArgumentException("Choose a reason for the report.");
        return moderation.report(matchId, actor.accountId(), input.reason(), text(input.details(), 300, "Details"));
    }

    /** The organizer deletes a match that hasn't started yet or is paused. */
    @PostMapping("/matches/{matchId}/delete")
    public java.util.Map<String, Boolean> delete(@PathVariable UUID matchId, @RequestAttribute("identity") AuthIdentity actor) {
        moderation.deleteByOwner(matchId, actor.accountId());
        live.recheckVisibility(matchId);
        live.publish(matchId);
        return java.util.Map.of("deleted", true);
    }

    @GetMapping("/admin/reports")
    public List<Moderation.AdminMatch> reports(@RequestAttribute("identity") AuthIdentity actor) {
        admins.require(actor.accountId());
        return moderation.matches(null);
    }
    @GetMapping("/admin/matches/{matchId}")
    public Moderation.AdminMatch match(@PathVariable UUID matchId, @RequestAttribute("identity") AuthIdentity actor) {
        admins.require(actor.accountId());
        var found = moderation.matches(matchId);
        if (found.isEmpty()) throw new com.raidzon.identity.service.AuthFailure(404, "MATCH_NOT_FOUND", "Match not found.");
        return found.getFirst();
    }
    @PostMapping("/admin/matches/{matchId}/remove")
    public Moderation.AdminMatch remove(@PathVariable UUID matchId, @RequestBody Moderation.RemoveInput input,
                                        @RequestAttribute("identity") AuthIdentity actor) {
        admins.require(actor.accountId());
        String reason = text(input == null ? null : input.reason(), 200, "Reason");
        if (reason == null) throw new IllegalArgumentException("Give a reason. The organizer sees it.");
        moderation.remove(matchId, actor.accountId(), reason);
        // Watchers still on the page are told the match is no longer available.
        live.recheckVisibility(matchId);
        live.publish(matchId);
        return match(matchId, actor);
    }
    @PostMapping("/admin/matches/{matchId}/restore")
    public Moderation.AdminMatch restore(@PathVariable UUID matchId, @RequestAttribute("identity") AuthIdentity actor) {
        admins.require(actor.accountId());
        moderation.restore(matchId);
        live.publish(matchId);
        return match(matchId, actor);
    }
    @PostMapping("/admin/matches/{matchId}/dismiss")
    public Moderation.AdminMatch dismiss(@PathVariable UUID matchId, @RequestAttribute("identity") AuthIdentity actor) {
        admins.require(actor.accountId());
        moderation.dismiss(matchId, actor.accountId());
        return match(matchId, actor);
    }

    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        if (trimmed.length() > max || trimmed.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
            throw new IllegalArgumentException(label + " must be up to " + max + " characters.");
        return trimmed;
    }
}
