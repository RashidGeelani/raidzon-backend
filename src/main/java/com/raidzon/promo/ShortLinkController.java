package com.raidzon.promo;

import com.raidzon.identity.service.AuthFailure;
import com.raidzon.identity.service.AuthService;
import com.raidzon.scorecard.live.LiveViewCache;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * Short links for posters (raidzon.com/m/AB2CD). Anyone may ask for the code of a public match or a
 * tournament; asking again returns the same code. Resolving checks the target is still public, so a
 * match that was hidden or removed stops resolving too.
 */
@RestController @Profile("postgres") @RequestMapping("/api/v1/public/short-links")
public class ShortLinkController {
    public record Input(String type, UUID id) {}
    public record Link(String code, String type, UUID id) {}
    /** Capitals and digits without 0, O, 1 and I, which look alike on a poster. */
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 5;
    static final int CREATES_PER_IP_HOUR = 120;

    private final JdbcTemplate jdbc;
    private final LiveViewCache matches;
    private final AuthService auth;
    private final SecureRandom random = new SecureRandom();
    public ShortLinkController(JdbcTemplate jdbc, LiveViewCache matches, AuthService auth) { this.jdbc = jdbc; this.matches = matches; this.auth = auth; }

    @PostMapping
    public Link create(@RequestBody Input input, jakarta.servlet.http.HttpServletRequest request) {
        if (input == null || input.id() == null || !("MATCH".equals(input.type()) || "TOURNAMENT".equals(input.type())))
            throw new IllegalArgumentException("Choose a match or a tournament.");
        requirePublic(input.type(), input.id());
        var existing = find(input.type(), input.id());
        if (existing != null) return existing;
        if (!auth.allow("short-link-ip", request.getRemoteAddr(), CREATES_PER_IP_HOUR))
            throw new AuthFailure(429, "RATE_LIMITED", "Too many links from this network. Try again later.");
        for (int attempt = 0; attempt < 8; attempt++) {
            // A clash on the code or on the target (someone else just made it) inserts nothing; then look again.
            jdbc.update("INSERT INTO short_links(code,target_type,target_id) VALUES (?,?,?) ON CONFLICT DO NOTHING", code(), input.type(), input.id());
            var made = find(input.type(), input.id());
            if (made != null) return made;
        }
        throw new IllegalStateException("No free short code.");
    }

    @GetMapping("/{code}")
    public Link resolve(@PathVariable String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        var rows = normalized.matches("[2-9A-HJ-NP-Z]{4,6}") ? jdbc.query("SELECT code,target_type,target_id FROM short_links WHERE code=?",
            (r, i) -> new Link(r.getString(1), r.getString(2), r.getObject(3, UUID.class)), normalized) : java.util.List.<Link>of();
        if (rows.isEmpty()) throw new AuthFailure(404, "LINK_NOT_FOUND", "This link doesn't lead anywhere.");
        var link = rows.getFirst();
        requirePublic(link.type(), link.id());
        return link;
    }

    private Link find(String type, UUID id) {
        var rows = jdbc.query("SELECT code,target_type,target_id FROM short_links WHERE target_type=? AND target_id=?",
            (r, i) -> new Link(r.getString(1), r.getString(2), r.getObject(3, UUID.class)), type, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Same rules as the public pages: a match must be watchable (throws as /public/matches does), a tournament must exist. */
    private void requirePublic(String type, UUID id) {
        if ("MATCH".equals(type)) matches.read(id);
        else if (jdbc.queryForObject("SELECT count(*) FROM tournaments WHERE id=?", Integer.class, id) == 0)
            throw new AuthFailure(404, "TOURNAMENT_NOT_FOUND", "Tournament not found.");
    }

    String code() {
        var code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return code.toString();
    }
}
