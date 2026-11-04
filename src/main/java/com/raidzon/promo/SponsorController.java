package com.raidzon.promo;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.identity.service.AuthFailure;
import com.raidzon.identity.service.AuthService;
import com.raidzon.moderation.AdminAccess;
import com.raidzon.tournament.repository.TournamentRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sponsors: the tournament's organizer adds, edits and removes them (the same check as every other
 * tournament change); RaidzOn admins can remove any. Logos are public and cached for a year (their
 * URL changes when the logo does).
 */
@RestController @Profile("postgres") @RequestMapping("/api/v1")
public class SponsorController {
    static final int UPLOADS_PER_ACCOUNT_HOUR = 30;

    private final Sponsors sponsors;
    private final TournamentRepository tournaments;
    private final AdminAccess admins;
    private final AuthService auth;
    public SponsorController(Sponsors sponsors, TournamentRepository tournaments, AdminAccess admins, AuthService auth) {
        this.sponsors = sponsors; this.tournaments = tournaments; this.admins = admins; this.auth = auth;
    }

    @GetMapping("/tournaments/{id}/sponsors")
    public List<Sponsors.Sponsor> list(@PathVariable UUID id, @RequestAttribute("identity") AuthIdentity actor) {
        tournaments.owned(id, actor.accountId(), false);
        return sponsors.list(id);
    }

    @Transactional @PostMapping("/tournaments/{id}/sponsors")
    public List<Sponsors.Sponsor> save(@PathVariable UUID id, @RequestBody Sponsors.Input input, @RequestAttribute("identity") AuthIdentity actor) {
        tournaments.owned(id, actor.accountId(), true); // locks the tournament, so two saves can't both pass the limits
        if (!auth.allow("sponsor-save", actor.accountId().toString(), UPLOADS_PER_ACCOUNT_HOUR))
            throw new AuthFailure(429, "RATE_LIMITED", "Too many sponsor changes. Try again in an hour.");
        sponsors.save(id, input);
        return sponsors.list(id);
    }

    @Transactional @PostMapping("/tournaments/{id}/sponsors/{sponsorId}/remove")
    public List<Sponsors.Sponsor> remove(@PathVariable UUID id, @PathVariable UUID sponsorId, @RequestAttribute("identity") AuthIdentity actor) {
        tournaments.owned(id, actor.accountId(), true);
        if (sponsors.remove(sponsorId, id) == null) throw new AuthFailure(404, "SPONSOR_NOT_FOUND", "Sponsor not found.");
        return sponsors.list(id);
    }

    /** Takes down a reported logo on any tournament. */
    @Transactional @PostMapping("/admin/sponsors/{sponsorId}/remove")
    public Map<String, Boolean> adminRemove(@PathVariable UUID sponsorId, @RequestAttribute("identity") AuthIdentity actor) {
        admins.require(actor.accountId());
        if (sponsors.remove(sponsorId, null) == null) throw new AuthFailure(404, "SPONSOR_NOT_FOUND", "Sponsor not found.");
        return Map.of("removed", true);
    }

    @GetMapping("/public/sponsors/{sponsorId}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable UUID sponsorId, HttpServletResponse response) {
        var logo = sponsors.logo(sponsorId);
        // setHeader replaces AuthFilter's "no-store"; ResponseEntity.cacheControl would only add a second value.
        response.setHeader("Cache-Control", CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable().getHeaderValue());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(logo.contentType()))
            .header("X-Content-Type-Options", "nosniff")
            .body(logo.bytes());
    }
}
