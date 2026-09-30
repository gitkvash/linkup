-- Password reset by emailed 6-digit code.
--
-- Until now an account was a username and a password hash and nothing else, so a forgotten
-- password had nowhere to be sent. Two additions:
--
-- 1. users.email. Nullable: every existing account, and every Google account, has none,
--    and the person adds one from their profile. Stored lowercase (the CHECK keeps a
--    writer that forgets from creating a second spelling), so a plain unique index is
--    already case-insensitive. NULLs are distinct in a unique index, so any number of
--    accounts may have no email. The address is not verified - a typo means the codes go
--    nowhere - because the only thing it can do is receive a code for *this* account.
--
-- 2. password_reset_codes. One row per account (the primary key), replaced when another
--    code is requested. Only a BCrypt hash of the code is stored: six digits are
--    guessable from any fast hash. failed_attempts is the brute-force limit - a million
--    codes and five guesses.
--
-- ---------------------------------------------------------------------------
-- Row-level security: deliberately NOT enabled on password_reset_codes, like
-- refresh_tokens (V28)
--
-- POST /auth/forgot-password and POST /auth/reset-password are unauthenticated - the code
-- is the credential - so app.current_user_id is unset on those connections and a policy
-- scoped to the caller would match zero rows on exactly the paths that use the table.
--
-- ---------------------------------------------------------------------------
-- Changing the password without a caller: app_reset_password()
--
-- users_update_policy (V16) lets an account update only its own row, and here there is no
-- caller. The alternatives were running the update as the SYSTEM role (reserved for work
-- with no acting user, and CLAUDE.md asks that its by-hand uses stay at one) or stamping a
-- user id the JWT filter never issued. Instead, one SECURITY DEFINER function, in the
-- spirit of V33, that can do exactly one thing: set the hash of an account that has an
-- unexpired reset code right now, and spend that code in the same statement. A compromised
-- query on the app role can therefore reach only accounts whose owner asked for a reset
-- in the last fifteen minutes - not every account, which a plain UPDATE grant on users
-- without the RLS policy would allow.
--
-- It never touches an account with no password_hash: a Google-only account does not gain a
-- password this way (V21's chk_users_auth_method is what says such a row is valid).
-- ---------------------------------------------------------------------------

ALTER TABLE users ADD COLUMN email VARCHAR(254);

ALTER TABLE users ADD CONSTRAINT chk_users_email_lowercase CHECK (email = lower(email));

CREATE UNIQUE INDEX ux_users_email ON users (email);

CREATE TABLE password_reset_codes (
    user_id         UUID        PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    code_hash       VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    failed_attempts INT         NOT NULL DEFAULT 0
);

-- Named, like V28: a table the app role can write should be a visible decision.
GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_codes TO linkup_app;

COMMENT ON TABLE password_reset_codes IS
    'At most one live emailed reset code per account (BCrypt hash, never the code). No RLS: only read from unauthenticated /auth endpoints, see V37.';

CREATE OR REPLACE FUNCTION app_reset_password(p_user uuid, p_hash text) RETURNS boolean
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    DELETE FROM password_reset_codes
     WHERE user_id = p_user AND expires_at > now();
    IF NOT FOUND THEN
        RETURN false;
    END IF;

    UPDATE users
       SET password_hash = p_hash
     WHERE user_id = p_user AND password_hash IS NOT NULL;
    RETURN FOUND;
END
$$;

COMMENT ON FUNCTION app_reset_password(uuid, text) IS
    'Sets the password of an account that has an unexpired reset code and spends the code. Returns false otherwise. See V37.';

REVOKE ALL ON FUNCTION app_reset_password(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_reset_password(uuid, text) TO linkup_app;
