-- Jersey numbers (0-999). Scorers pick raiders and defenders by the number on the shirt,
-- so every match player carries one (stored in the match state). Tournament rosters gain an
-- optional number that pre-fills match setup; saved-team numbers widen from 0-99 to 0-999.
ALTER TABLE tournament_roster_players
    ADD COLUMN jersey integer CHECK (jersey IS NULL OR jersey BETWEEN 0 AND 999);
CREATE UNIQUE INDEX tournament_roster_jersey ON tournament_roster_players(team_id, jersey) WHERE jersey IS NOT NULL;

ALTER TABLE team_members DROP CONSTRAINT team_members_jersey_check;
ALTER TABLE team_members ADD CONSTRAINT team_members_jersey_check CHECK (jersey BETWEEN 0 AND 999);
