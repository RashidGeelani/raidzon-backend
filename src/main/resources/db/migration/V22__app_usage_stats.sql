-- Usage numbers for the admin "App stats" card. No analytics service: just when an account was
-- last seen (updated at most hourly) and one row per installed copy of the app.
ALTER TABLE user_accounts ADD COLUMN last_seen_at timestamptz;
CREATE INDEX user_accounts_last_seen ON user_accounts(last_seen_at);

CREATE TABLE app_installs (
    id uuid PRIMARY KEY,
    user_id uuid REFERENCES user_accounts(id),
    install_id text NOT NULL UNIQUE CHECK (install_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    platform text NOT NULL CHECK (char_length(platform) <= 20),
    source text NOT NULL CHECK (source IN ('appinstalled', 'standalone_launch')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX app_installs_created ON app_installs(created_at);

ALTER TABLE app_installs ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON app_installs FROM %I', r);
        END IF;
    END LOOP;
END
$$;
