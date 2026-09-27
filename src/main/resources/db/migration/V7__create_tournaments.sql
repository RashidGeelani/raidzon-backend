CREATE TABLE tournaments (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL REFERENCES user_accounts(id),
    name text NOT NULL,
    venue text NOT NULL,
    starts_on date NOT NULL,
    half_minutes integer NOT NULL CHECK (half_minutes BETWEEN 1 AND 60),
    raid_seconds integer NOT NULL CHECK (raid_seconds BETWEEN 5 AND 120),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tournaments_owner ON tournaments(owner_account_id, created_at DESC);
CREATE TABLE tournament_teams (
    id uuid PRIMARY KEY,
    tournament_id uuid NOT NULL REFERENCES tournaments(id),
    name text NOT NULL,
    UNIQUE(tournament_id,id)
);
CREATE UNIQUE INDEX tournament_team_names ON tournament_teams(tournament_id,lower(name));
CREATE TABLE tournament_fixtures (
    id uuid PRIMARY KEY,
    tournament_id uuid NOT NULL REFERENCES tournaments(id),
    team_a_id uuid NOT NULL,
    team_b_id uuid NOT NULL,
    scheduled_at timestamptz,
    match_id uuid UNIQUE REFERENCES matches(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (team_a_id <> team_b_id),
    FOREIGN KEY (tournament_id,team_a_id) REFERENCES tournament_teams(tournament_id,id),
    FOREIGN KEY (tournament_id,team_b_id) REFERENCES tournament_teams(tournament_id,id)
);
