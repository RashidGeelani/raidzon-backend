CREATE TABLE public_scorecards (
    match_id uuid PRIMARY KEY REFERENCES matches(id),
    share_id uuid NOT NULL UNIQUE,
    published boolean NOT NULL DEFAULT true
);
