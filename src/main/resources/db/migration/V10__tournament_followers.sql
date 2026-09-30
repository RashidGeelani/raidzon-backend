CREATE TABLE tournament_followers (
    tournament_id uuid NOT NULL REFERENCES tournaments(id),
    account_id uuid NOT NULL REFERENCES user_accounts(id),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tournament_id, account_id)
);
CREATE INDEX tournament_followers_account ON tournament_followers(account_id);
