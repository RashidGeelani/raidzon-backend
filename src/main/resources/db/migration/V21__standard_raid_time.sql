-- Raids are always the standard 30 seconds; it is no longer a tournament setting.
-- Matches already scored keep the raid time they were played with (it lives in their own state).
UPDATE tournaments SET raid_seconds = 30 WHERE raid_seconds <> 30;
ALTER TABLE tournaments ADD CONSTRAINT tournaments_standard_raid_time CHECK (raid_seconds = 30);
