-- Tournament formats: league, knockout, and groups followed by knockout.
ALTER TABLE tournaments
    ADD COLUMN format text NOT NULL DEFAULT 'LEAGUE' CHECK (format IN ('LEAGUE', 'KNOCKOUT', 'GROUPS_KNOCKOUT')),
    ADD COLUMN group_count integer NOT NULL DEFAULT 1 CHECK (group_count BETWEEN 1 AND 8),
    ADD COLUMN advance_per_group integer NOT NULL DEFAULT 2 CHECK (advance_per_group IN (1, 2, 4)),
    ADD COLUMN third_place boolean NOT NULL DEFAULT false;

CREATE TABLE tournament_groups (
    id uuid PRIMARY KEY,
    tournament_id uuid NOT NULL REFERENCES tournaments(id),
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 20),
    position integer NOT NULL CHECK (position BETWEEN 0 AND 7),
    UNIQUE (tournament_id, position),
    UNIQUE (tournament_id, id)
);

-- A team's group (groups format) and its order inside the group / the knockout draw.
ALTER TABLE tournament_teams
    ADD COLUMN group_id uuid,
    ADD COLUMN seed integer,
    ADD CONSTRAINT tournament_teams_group_fk FOREIGN KEY (tournament_id, group_id) REFERENCES tournament_groups(tournament_id, id);

-- Knockout fixtures start without teams: each side names where its team comes from
-- (T:<team>, G:<group>:<rank>, W:<fixture> winner, L:<fixture> loser) and is filled in when decided.
ALTER TABLE tournament_fixtures
    ALTER COLUMN team_a_id DROP NOT NULL,
    ALTER COLUMN team_b_id DROP NOT NULL,
    ADD COLUMN stage text NOT NULL DEFAULT 'LEAGUE' CHECK (stage IN ('LEAGUE', 'GROUP', 'KNOCKOUT', 'THIRD_PLACE')),
    ADD COLUMN group_id uuid,
    ADD COLUMN round integer CHECK (round IS NULL OR round BETWEEN 1 AND 20),
    ADD COLUMN slot integer CHECK (slot IS NULL OR slot BETWEEN 0 AND 255),
    ADD COLUMN source_a text CHECK (source_a IS NULL OR char_length(source_a) <= 80),
    ADD COLUMN source_b text CHECK (source_b IS NULL OR char_length(source_b) <= 80),
    ADD COLUMN generated boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT tournament_fixtures_group_fk FOREIGN KEY (tournament_id, group_id) REFERENCES tournament_groups(tournament_id, id),
    ADD CONSTRAINT tournament_fixtures_teams_or_sources CHECK ((team_a_id IS NOT NULL OR source_a IS NOT NULL) AND (team_b_id IS NOT NULL OR source_b IS NOT NULL));
CREATE INDEX tournament_fixtures_stage ON tournament_fixtures(tournament_id, stage, round, slot);

ALTER TABLE tournament_groups ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON tournament_groups FROM %I', r);
        END IF;
    END LOOP;
END
$$;
