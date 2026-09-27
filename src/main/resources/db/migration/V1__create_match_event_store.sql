CREATE TABLE matches (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    scoring_session_id uuid NOT NULL,
    ruleset_version text NOT NULL,
    creation_fingerprint char(64) NOT NULL,
    initial_state jsonb NOT NULL,
    projection jsonb NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE match_events (
    match_id uuid NOT NULL REFERENCES matches(id),
    event_id uuid NOT NULL,
    sequence integer NOT NULL CHECK (sequence > 0),
    base_version integer NOT NULL CHECK (base_version >= 0),
    request_fingerprint char(64) NOT NULL,
    request jsonb NOT NULL,
    before_state jsonb NOT NULL,
    after_state jsonb NOT NULL,
    components jsonb NOT NULL,
    summary text NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, event_id),
    UNIQUE (match_id, sequence),
    CHECK (sequence = base_version + 1)
);

-- Corrections append new events. Reversal never updates or removes old events.
CREATE FUNCTION reject_match_event_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Match event history is append-only';
END;
$$;

CREATE TRIGGER match_events_append_only
BEFORE UPDATE OR DELETE ON match_events
FOR EACH ROW EXECUTE FUNCTION reject_match_event_mutation();

CREATE INDEX matches_owner_updated ON matches (owner_account_id, updated_at DESC);
