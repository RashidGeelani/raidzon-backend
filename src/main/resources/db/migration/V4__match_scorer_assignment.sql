ALTER TABLE matches ADD COLUMN scoring_account_id uuid;
UPDATE matches SET scoring_account_id=owner_account_id;
ALTER TABLE matches ALTER COLUMN scoring_account_id SET NOT NULL;
CREATE TABLE match_scorer_assignments (
    match_id uuid PRIMARY KEY REFERENCES matches(id),
    scorer_account_id uuid NOT NULL REFERENCES user_accounts(id),
    accepted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
