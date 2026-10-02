-- Tournament squads: registered from saved teams, up to 20 players, and one team per player per tournament.

-- A tournament team can come from a saved team; its roster is a snapshot copied at registration.
ALTER TABLE tournament_teams ADD COLUMN team_id uuid REFERENCES teams(id);
CREATE UNIQUE INDEX tournament_teams_saved_team ON tournament_teams(tournament_id, team_id) WHERE team_id IS NOT NULL;

-- Rosters reference the global player identity, so the one-team rule holds however a phone was typed.
INSERT INTO player_profiles(id, phone, initial_name, claimed_by)
SELECT gen_random_uuid(), r.phone, min(r.name), (SELECT u.id FROM user_accounts u WHERE u.phone = r.phone)
FROM tournament_roster_players r
WHERE NOT EXISTS (SELECT 1 FROM player_profiles p WHERE p.phone = r.phone)
GROUP BY r.phone;
ALTER TABLE tournament_roster_players ADD COLUMN profile_id uuid REFERENCES player_profiles(id);
UPDATE tournament_roster_players r SET profile_id = p.id FROM player_profiles p WHERE p.phone = r.phone;
ALTER TABLE tournament_roster_players ALTER COLUMN profile_id SET NOT NULL;
ALTER TABLE tournament_roster_players ADD CONSTRAINT tournament_roster_one_team_per_player UNIQUE (tournament_id, profile_id);

-- Squads of up to 20 (7 required). Match day still uses 7 starters + up to 5 substitutes.
ALTER TABLE tournament_roster_players DROP CONSTRAINT tournament_roster_players_position_check;
ALTER TABLE tournament_roster_players ADD CONSTRAINT tournament_roster_players_position_check CHECK (position BETWEEN 0 AND 19);
