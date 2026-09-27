CREATE TABLE user_accounts (
    id uuid PRIMARY KEY,
    phone text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE auth_devices (
    id uuid PRIMARY KEY,
    secret_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE auth_challenges (
    id uuid PRIMARY KEY,
    phone text NOT NULL,
    device_id uuid NOT NULL,
    device_secret_hash char(64) NOT NULL,
    code_hash char(64) NOT NULL,
    expires_at bigint NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    delivered boolean NOT NULL DEFAULT false,
    consumed boolean NOT NULL DEFAULT false
);
CREATE TABLE auth_rate_limits (
    key text PRIMARY KEY,
    window_start bigint NOT NULL,
    last_request bigint NOT NULL,
    requests integer NOT NULL
);
CREATE TABLE auth_tokens (
    token_hash char(64) PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES user_accounts(id),
    device_id uuid NOT NULL REFERENCES auth_devices(id),
    expires_at bigint NOT NULL,
    revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX auth_tokens_expiry ON auth_tokens(expires_at);
CREATE TABLE player_profiles (
    id uuid PRIMARY KEY,
    phone text NOT NULL UNIQUE,
    initial_name text NOT NULL,
    claimed_by uuid REFERENCES user_accounts(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE match_player_links (
    match_id uuid NOT NULL REFERENCES matches(id),
    local_player_id uuid NOT NULL,
    profile_id uuid NOT NULL REFERENCES player_profiles(id),
    PRIMARY KEY(match_id, local_player_id)
);
