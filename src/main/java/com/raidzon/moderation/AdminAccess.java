package com.raidzon.moderation;

import com.raidzon.identity.service.AuthFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * RaidzOn admins are the accounts whose verified phone is listed in RAIDZON_ADMIN_PHONES
 * (comma-separated, international format, e.g. +919876543210). Nobody is an admin when it is empty.
 */
@Component @Profile("postgres")
public class AdminAccess {
    private final JdbcTemplate jdbc;
    private final Set<String> phones;
    public AdminAccess(JdbcTemplate jdbc, @Value("${RAIDZON_ADMIN_PHONES:}") String phones) {
        this.jdbc = jdbc;
        this.phones = Set.copyOf(Arrays.stream(phones.split(",")).map(p -> p.replaceAll("[\\s-]", "")).filter(p -> !p.isEmpty()).toList());
    }
    public boolean isAdmin(UUID account) {
        if (phones.isEmpty()) return false;
        var phone = jdbc.queryForList("SELECT phone FROM user_accounts WHERE id=?", String.class, account);
        return !phone.isEmpty() && phones.contains(phone.getFirst());
    }
    public void require(UUID account) {
        if (!isAdmin(account)) throw new AuthFailure(403, "ADMIN_ONLY", "Only RaidzOn admins can do this.");
    }
}
