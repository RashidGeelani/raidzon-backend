package com.raidzon.moderation;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.identity.service.AuthFailure;
import com.raidzon.identity.service.AuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Install pings from the app (public) and the admin "App stats" numbers. */
@RestController @Profile("postgres") @RequestMapping("/api/v1")
public class AppStatsController {
    public record InstallInput(String installId, String source) {}
    public record AppStats(long totalUsers, long activeToday, long active7Days, long active30Days, long newUsers7Days,
                           long totalInstalls, long installs7Days, long matchesScored, long matchesScored7Days) {}
    static final Set<String> SOURCES = Set.of("appinstalled", "standalone_launch");

    private final JdbcTemplate jdbc;
    private final AdminAccess admins;
    private final AuthService auth;
    public AppStatsController(JdbcTemplate jdbc, AdminAccess admins, AuthService auth) { this.jdbc = jdbc; this.admins = admins; this.auth = auth; }

    static final int INSTALLS_PER_IP_HOUR = 20;

    /** One row per install ID; repeats are ignored. Linked to the account when a valid session is sent. Limited per client IP. */
    @PostMapping("/public/installs")
    public Map<String, Boolean> install(@RequestBody InstallInput input, @RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                        jakarta.servlet.http.HttpServletRequest request) {
        if (input == null || input.installId() == null || !input.installId().matches("[A-Za-z0-9_-]{8,64}") || !SOURCES.contains(input.source()))
            throw new IllegalArgumentException("Invalid install.");
        // getRemoteAddr() is the real client IP (RemoteIpValve reads Render's X-Forwarded-For), so new install IDs can't dodge it.
        if (!auth.allow("install-ip", request.getRemoteAddr(), INSTALLS_PER_IP_HOUR))
            throw new AuthFailure(429, "RATE_LIMITED", "Too many installs from this network. Try again later.");
        UUID user = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try { user = auth.authenticate(authorization.substring(7)).accountId(); } catch (AuthFailure ignored) { /* count it anonymously */ }
        }
        int inserted = jdbc.update("INSERT INTO app_installs(id,user_id,install_id,platform,source) VALUES (?,?,?,?,?) ON CONFLICT (install_id) DO NOTHING",
            UUID.randomUUID(), user, input.installId(), platform(userAgent), input.source());
        return Map.of("recorded", inserted == 1);
    }

    @GetMapping("/admin/stats")
    public AppStats stats(@RequestAttribute("identity") AuthIdentity actor) {
        admins.require(actor.accountId());
        return jdbc.queryForObject("""
            SELECT
              (SELECT count(*) FROM user_accounts),
              (SELECT count(*) FROM user_accounts WHERE last_seen_at > now()-interval '1 day'),
              (SELECT count(*) FROM user_accounts WHERE last_seen_at > now()-interval '7 days'),
              (SELECT count(*) FROM user_accounts WHERE last_seen_at > now()-interval '30 days'),
              (SELECT count(*) FROM user_accounts WHERE created_at > now()-interval '7 days'),
              (SELECT count(*) FROM app_installs),
              (SELECT count(*) FROM app_installs WHERE created_at > now()-interval '7 days'),
              (SELECT count(*) FROM matches WHERE version > 0 AND deleted_at IS NULL AND removed_at IS NULL),
              (SELECT count(*) FROM matches WHERE version > 0 AND deleted_at IS NULL AND removed_at IS NULL AND created_at > now()-interval '7 days')
            """, (r, i) -> new AppStats(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5),
                r.getLong(6), r.getLong(7), r.getLong(8), r.getLong(9)));
    }

    static String platform(String userAgent) {
        String ua = userAgent == null ? "" : userAgent;
        if (ua.contains("Android")) return "android";
        if (ua.matches(".*(iPhone|iPad|iPod).*")) return "ios";
        if (ua.contains("Windows")) return "windows";
        if (ua.contains("Macintosh")) return "mac";
        if (ua.contains("Linux") || ua.contains("CrOS")) return "linux";
        return "other";
    }
}
