-- A league or group of 21+ teams needs more than 20 round-robin rounds (n-1, or n when n is odd).
ALTER TABLE tournament_fixtures DROP CONSTRAINT IF EXISTS tournament_fixtures_round_check;
ALTER TABLE tournament_fixtures ADD CONSTRAINT tournament_fixtures_round_check
    CHECK (round IS NULL OR round BETWEEN 1 AND 64);
