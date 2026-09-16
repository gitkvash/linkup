-- Google Sign-In accounts have no password: password_hash can no longer be
-- NOT NULL, and a Google-authenticated user is identified by google_id
-- instead. The check constraint keeps every row identifiable by at least one
-- of the two auth methods - a row with neither could never log in again.

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users ADD COLUMN google_id VARCHAR(255) UNIQUE;

ALTER TABLE users ADD CONSTRAINT chk_users_auth_method
    CHECK (password_hash IS NOT NULL OR google_id IS NOT NULL);
