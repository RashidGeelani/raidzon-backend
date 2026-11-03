-- Tournament sponsors: up to 4 per tournament (checked in the service), at most one Title sponsor.
-- Logos are small (the phone shrinks them to 400 px wide), so they live in the database and are
-- served by the backend with long caching.
CREATE TABLE tournament_sponsors (
    id uuid PRIMARY KEY,
    tournament_id uuid NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    level text NOT NULL CHECK (level IN ('TITLE', 'PARTNER')),
    link text CHECK (link IS NULL OR char_length(link) <= 200),
    logo bytea NOT NULL CHECK (octet_length(logo) BETWEEN 1 AND 153600),
    content_type text NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    sort_order int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tournament_sponsors_tournament ON tournament_sponsors(tournament_id, sort_order);
CREATE UNIQUE INDEX tournament_sponsors_one_title ON tournament_sponsors(tournament_id) WHERE level = 'TITLE';

-- Short links printed on posters (raidzon.com/m/AB2CD): one code per match or tournament.
-- Codes use capitals and digits without 0, O, 1 and I so they can be typed from a poster.
CREATE TABLE short_links (
    code text PRIMARY KEY CHECK (code ~ '^[2-9A-HJ-NP-Z]{4,6}$'),
    target_type text NOT NULL CHECK (target_type IN ('MATCH', 'TOURNAMENT')),
    target_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (target_type, target_id)
);
