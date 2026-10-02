-- Teams ask to join another organizer's tournament; the organizer approves or rejects.
-- Player phones reach the organizer only on approval, when the squad is copied as the roster.

ALTER TABLE tournaments ADD COLUMN registration_open boolean NOT NULL DEFAULT true;

CREATE TABLE tournament_join_requests (
    id uuid PRIMARY KEY,
    tournament_id uuid NOT NULL REFERENCES tournaments(id),
    team_id uuid NOT NULL REFERENCES teams(id),
    requested_by uuid NOT NULL REFERENCES user_accounts(id),
    message text CHECK (message IS NULL OR char_length(message) BETWEEN 1 AND 200),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    decision_note text CHECK (decision_note IS NULL OR char_length(decision_note) BETWEEN 1 AND 200),
    decided_by uuid REFERENCES user_accounts(id),
    decided_at timestamptz,
    tournament_team_id uuid REFERENCES tournament_teams(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CHECK ((status = 'APPROVED') = (tournament_team_id IS NOT NULL))
);
-- At most one open request per team per tournament.
CREATE UNIQUE INDEX tournament_join_requests_one_pending ON tournament_join_requests(tournament_id, team_id) WHERE status = 'PENDING';
CREATE INDEX tournament_join_requests_tournament ON tournament_join_requests(tournament_id, created_at DESC);
CREATE INDEX tournament_join_requests_team ON tournament_join_requests(team_id, created_at DESC);

ALTER TABLE tournament_join_requests ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON tournament_join_requests FROM %I', r);
        END IF;
    END LOOP;
END
$$;
