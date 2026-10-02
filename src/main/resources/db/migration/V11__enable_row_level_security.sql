-- Supabase exposes the public schema through its auto-generated REST API (PostgREST).
-- Enable Row Level Security on every table with NO policies, so the anon and
-- authenticated API roles can read or write nothing. The backend connects as the
-- table owner (postgres), which bypasses RLS, so the API is unaffected.
-- Any table added in a later migration must also run: ALTER TABLE ... ENABLE ROW LEVEL SECURITY;
DO $$
DECLARE
    t record;
    r text;
BEGIN
    -- flyway_schema_history is skipped: Flyway holds a lock on it during migration, so altering
    -- it here would wait forever. The REVOKE below still removes API access to it.
    FOR t IN SELECT tablename FROM pg_tables
             WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history' LOOP
        EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', current_schema(), t.tablename);
    END LOOP;

    -- Supabase-only roles. Skipped on plain PostgreSQL (local and test databases).
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM %I', current_schema(), r);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM %I', current_schema(), r);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA %I FROM %I', current_schema(), r);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I REVOKE ALL ON TABLES FROM %I', current_schema(), r);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM %I', current_schema(), r);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I REVOKE ALL ON FUNCTIONS FROM %I', current_schema(), r);
        END IF;
    END LOOP;
END
$$;
