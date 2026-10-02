-- Reusable teams that exist outside tournaments, plus a player-owned display name.
-- A player's own name (display_name) is set only by the verified player and is shown
-- everywhere; the organizer's squad_name is only a fallback for unclaimed players.

ALTER TABLE player_profiles ADD COLUMN display_name text
    CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 80);
ALTER TABLE player_profiles ADD COLUMN display_name_changed_at timestamptz;

CREATE TABLE player_name_history (
    id bigserial PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES player_profiles(id),
    old_name text,
    new_name text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX player_name_history_profile ON player_name_history(profile_id, changed_at DESC);

CREATE TABLE teams (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL REFERENCES user_accounts(id),
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    city text CHECK (city IS NULL OR char_length(city) BETWEEN 1 AND 60),
    archived boolean NOT NULL DEFAULT false,
    revision integer NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX teams_owner ON teams(owner_account_id, created_at DESC);

-- Managers and coaches are identified by phone (through a player profile), so they can be
-- added before they ever sign in. Their permissions apply once they sign in with that phone.
CREATE TABLE team_staff (
    team_id uuid NOT NULL REFERENCES teams(id),
    profile_id uuid NOT NULL REFERENCES player_profiles(id),
    role text NOT NULL CHECK (role IN ('MANAGER', 'COACH')),
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 70),
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, profile_id, role)
);
CREATE INDEX team_staff_profile ON team_staff(profile_id);

-- Squad: 7 required, 12 recommended, 20 maximum (cap enforced in the service under a team lock).
-- Leaving keeps the row (left_at) so history is preserved.
CREATE TABLE team_members (
    id uuid PRIMARY KEY,
    team_id uuid NOT NULL REFERENCES teams(id),
    profile_id uuid NOT NULL REFERENCES player_profiles(id),
    squad_name text NOT NULL CHECK (char_length(squad_name) BETWEEN 1 AND 70),
    jersey integer CHECK (jersey BETWEEN 0 AND 99),
    playing_role text CHECK (playing_role IN ('RAIDER', 'DEFENDER', 'ALL_ROUNDER')),
    leadership text CHECK (leadership IN ('CAPTAIN', 'VICE_CAPTAIN')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    left_at timestamptz,
    CHECK (left_at IS NULL OR leadership IS NULL)
);
CREATE UNIQUE INDEX team_members_active_profile ON team_members(team_id, profile_id) WHERE left_at IS NULL;
CREATE UNIQUE INDEX team_members_active_jersey ON team_members(team_id, jersey) WHERE left_at IS NULL AND jersey IS NOT NULL;
-- One member row holds one leadership value, so captain and vice-captain are always different people.
CREATE UNIQUE INDEX team_members_one_captain ON team_members(team_id) WHERE leadership = 'CAPTAIN' AND left_at IS NULL;
CREATE UNIQUE INDEX team_members_one_vice_captain ON team_members(team_id) WHERE leadership = 'VICE_CAPTAIN' AND left_at IS NULL;
CREATE INDEX team_members_profile ON team_members(profile_id);

-- Same protection as V11 for the new tables.
ALTER TABLE player_name_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE teams ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_members ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON player_name_history, teams, team_staff, team_members FROM %I', r);
            EXECUTE format('REVOKE ALL ON SEQUENCE player_name_history_id_seq FROM %I', r);
        END IF;
    END LOOP;
END
$$;
