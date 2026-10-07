package com.raidzon.tournament.service;

import com.raidzon.tournament.dto.Tournament;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandardRaidTimeTest {
    final TournamentService service = new TournamentService(null, null);
    Tournament withRaid(int seconds) {
        return new Tournament(UUID.randomUUID(), "Cup", "Court", LocalDate.of(2026, 11, 1), 20, seconds);
    }
    @Test void tournamentsCannotChangeTheRaidTime() {
        for (int seconds : new int[]{5, 29, 31, 45, 120}) {
            var error = assertThrows(IllegalArgumentException.class, () -> service.create(withRaid(seconds), UUID.randomUUID()));
            assertEquals("Raids are the standard 30 seconds.", error.getMessage());
        }
    }
    @Test void thirtySecondsPassesTheRaidCheck() {
        // With the standard time the check passes and the service moves on to saving (no repository here).
        assertThrows(NullPointerException.class, () -> service.create(withRaid(30), UUID.randomUUID()));
    }
}
