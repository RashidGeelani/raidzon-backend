ALTER TABLE tournament_teams ADD COLUMN roster_revision integer NOT NULL DEFAULT 0 CHECK (roster_revision >= 0);
CREATE TABLE tournament_roster_players (
    tournament_id uuid NOT NULL,
    team_id uuid NOT NULL,
    position integer NOT NULL CHECK (position BETWEEN 0 AND 11),
    name text NOT NULL,
    phone text NOT NULL,
    PRIMARY KEY (team_id, position),
    UNIQUE (tournament_id, phone),
    FOREIGN KEY (tournament_id, team_id) REFERENCES tournament_teams(tournament_id, id)
);
