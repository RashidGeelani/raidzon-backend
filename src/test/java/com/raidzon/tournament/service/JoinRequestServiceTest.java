package com.raidzon.tournament.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoinRequestServiceTest {
    @Test void notesAreOptionalTrimmedAndShort() {
        assertNull(JoinRequestService.note(null));
        assertNull(JoinRequestService.note("   "));
        assertEquals("See you there", JoinRequestService.note("  See you there "));
        assertEquals("Line one\nLine two", JoinRequestService.note("Line one\nLine two"));
        assertThrows(IllegalArgumentException.class, () -> JoinRequestService.note("x".repeat(201)));
        assertThrows(IllegalArgumentException.class, () -> JoinRequestService.note("bad\u0007note"));
    }
}
