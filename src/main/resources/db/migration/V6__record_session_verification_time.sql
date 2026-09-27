ALTER TABLE auth_tokens ADD COLUMN verified_at bigint NOT NULL DEFAULT 0;
