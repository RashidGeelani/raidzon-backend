package com.raidzon.team.service;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeamServiceRulesTest {
    @Test void namesAreTrimmedAndBounded() {
        assertEquals("Valley Raiders", TeamService.name("  Valley Raiders ", 60, "team name"));
        assertThrows(IllegalArgumentException.class, () -> TeamService.name("   ", 60, "team name"));
        assertThrows(IllegalArgumentException.class, () -> TeamService.name("x".repeat(61), 60, "team name"));
        assertThrows(IllegalArgumentException.class, () -> TeamService.name("bad\u0007name", 60, "team name"));
        assertNull(TeamService.city(" "));
    }
    @Test void phonesMustBeCanonicalInternationalNumbers() {
        assertEquals("+919876543210", TeamService.phone("+919876543210"));
        assertThrows(IllegalArgumentException.class, () -> TeamService.phone("9876543210"));
        assertThrows(IllegalArgumentException.class, () -> TeamService.phone("+0123456789"));
    }
    @Test void jerseyAndRolesAreValidated() {
        assertEquals(999, TeamService.jersey(999));
        assertNull(TeamService.jersey(null));
        assertThrows(IllegalArgumentException.class, () -> TeamService.jersey(1000));
        assertThrows(IllegalArgumentException.class, () -> TeamService.jersey(-1));
        assertEquals("RAIDER", TeamService.playingRole("RAIDER"));
        assertNull(TeamService.playingRole(""));
        assertThrows(IllegalArgumentException.class, () -> TeamService.playingRole("GOALKEEPER"));
        assertEquals("COACH", TeamService.staffRole("COACH"));
        assertThrows(IllegalArgumentException.class, () -> TeamService.staffRole("OWNER"));
    }
    @Test void captainAndViceCaptainMustDiffer() {
        UUID a = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> TeamService.checkLeadership(a, a));
        TeamService.checkLeadership(a, UUID.randomUUID());
        TeamService.checkLeadership(null, null);
    }
}
