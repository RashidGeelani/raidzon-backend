-- "Continue scoring here": the scoring account moves a live match to a new phone after a fresh OTP.
-- One row per attempt (accepted or refused) explains who moved which match when, and drives the
-- rate limit (a few attempts per account and match per hour).
CREATE TABLE match_takeovers (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    match_id uuid NOT NULL REFERENCES matches(id),
    account_id uuid NOT NULL REFERENCES user_accounts(id),
    from_session uuid NOT NULL,
    to_session uuid NOT NULL,
    result text NOT NULL CHECK (char_length(result) BETWEEN 1 AND 40),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX match_takeovers_match ON match_takeovers(match_id, account_id, created_at DESC);

-- Same lockdown as every other table: only the backend reads it, never Supabase's public API roles.
ALTER TABLE match_takeovers ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON match_takeovers FROM %I', r);
        END IF;
    END LOOP;
END
$$;
