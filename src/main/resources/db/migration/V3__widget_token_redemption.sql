CREATE TABLE widget_token_redemptions (
    token_hash varchar(64) PRIMARY KEY,
    redeemed_at bigint NOT NULL
);
