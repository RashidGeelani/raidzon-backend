-- Player stats rule from 2026-10-20: friendlies and tournaments under 4 teams / 3 completed matches
-- no longer add to a player's profile. Matches already on the server keep counting as before:
-- existing rows get legacy_stats=true, every match created after this migration gets false.
ALTER TABLE matches ADD COLUMN legacy_stats boolean NOT NULL DEFAULT true;
ALTER TABLE matches ALTER COLUMN legacy_stats SET DEFAULT false;
