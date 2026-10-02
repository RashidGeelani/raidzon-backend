-- In-app notifications (no SMS/WhatsApp). One row per recipient.
CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES user_accounts(id),
    kind text NOT NULL CHECK (kind IN ('JOIN_REQUEST_RECEIVED', 'JOIN_REQUEST_WITHDRAWN', 'JOIN_REQUEST_APPROVED', 'JOIN_REQUEST_REJECTED')),
    title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    body text CHECK (body IS NULL OR char_length(body) <= 300),
    tournament_id uuid REFERENCES tournaments(id),
    team_id uuid REFERENCES teams(id),
    join_request_id uuid REFERENCES tournament_join_requests(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz
);
CREATE INDEX notifications_account ON notifications(account_id, created_at DESC);
CREATE INDEX notifications_unread ON notifications(account_id) WHERE read_at IS NULL;
CREATE INDEX notifications_request ON notifications(join_request_id);

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON notifications FROM %I', r);
        END IF;
    END LOOP;
END
$$;
