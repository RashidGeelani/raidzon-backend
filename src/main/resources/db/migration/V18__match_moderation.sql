-- Practice matches (quick match with filled-in names) never count towards profiles or leaderboards.
-- A removed match (taken down by a RaidzOn admin, e.g. fake scores) is hidden from public pages,
-- unlinked from its tournament fixture and left out of every stat.
ALTER TABLE matches
    ADD COLUMN practice boolean NOT NULL DEFAULT false,
    ADD COLUMN removed_at timestamptz,
    ADD COLUMN removed_by uuid REFERENCES user_accounts(id),
    ADD COLUMN removed_reason text CHECK (removed_reason IS NULL OR char_length(removed_reason) BETWEEN 1 AND 200),
    ADD CHECK ((removed_at IS NULL) = (removed_by IS NULL));

-- Viewers report a match they believe is fake or wrong; admins review open reports.
CREATE TABLE match_reports (
    id uuid PRIMARY KEY,
    match_id uuid NOT NULL REFERENCES matches(id),
    reporter_account_id uuid NOT NULL REFERENCES user_accounts(id),
    reason text NOT NULL CHECK (reason IN ('FAKE_MATCH', 'WRONG_SCORE', 'WRONG_PLAYERS', 'OTHER')),
    details text CHECK (details IS NULL OR char_length(details) BETWEEN 1 AND 300),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DISMISSED', 'ACTIONED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by uuid REFERENCES user_accounts(id),
    CHECK ((status = 'OPEN') = (resolved_at IS NULL)),
    UNIQUE (match_id, reporter_account_id)
);
CREATE INDEX match_reports_open ON match_reports(created_at) WHERE status = 'OPEN';
CREATE INDEX match_reports_reporter ON match_reports(reporter_account_id, created_at DESC);

-- The organizer is told when an admin removes their match.
ALTER TABLE notifications DROP CONSTRAINT notifications_kind_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_kind_check CHECK (kind IN (
    'JOIN_REQUEST_RECEIVED', 'JOIN_REQUEST_WITHDRAWN', 'JOIN_REQUEST_APPROVED', 'JOIN_REQUEST_REJECTED', 'MATCH_REMOVED'));

-- Same lockdown as every other table: only the backend reads reports, never Supabase's public API roles.
ALTER TABLE match_reports ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON match_reports FROM %I', r);
        END IF;
    END LOOP;
END
$$;
