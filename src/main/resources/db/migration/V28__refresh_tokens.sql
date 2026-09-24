-- Server-side state for refresh tokens, so they can be rotated and revoked.
--
-- Until now a refresh token was a stateless 180-day JWT. Refreshing handed back a new one
-- but the old one stayed valid until it expired, and there was no way to sign out: a token
-- copied off a phone kept minting sessions for half a year, whatever the owner did.
--
-- One row per refresh token ever issued, keyed by the token's `jti` claim. A refresh
-- revokes its row (conditionally - `WHERE revoked_at IS NULL`, so two concurrent refreshes
-- cannot both rotate the same token) and points `replaced_by` at the successor. A revoked
-- token presented again is either a client retrying after a lost response (within a few
-- seconds of the rotation) or a stolen copy, in which case every token of that user is
-- revoked. `replaced_by` is what tells those apart from a plain sign-out, which revokes
-- without a successor. It is deliberately not a foreign key: the successor row is inserted
-- after the rotation that names it, and a pruned successor must not block pruning.
--
-- ---------------------------------------------------------------------------
-- Row-level security: deliberately NOT enabled, like event_publication (see V16)
--
-- Every access to this table happens before there is an authenticated caller.
-- POST /auth/refresh and POST /auth/logout are unauthenticated endpoints - the refresh
-- token itself is the credential - so app.current_user_id is unset on those connections
-- and app_current_user() is NULL. A policy scoped to the caller would match zero rows on
-- exactly the paths that use the table: every refresh would look like an unknown token
-- and sign the user out, with no error anywhere. Login and registration write the first
-- row under the same conditions. A USING (true) policy would be decoration.
--
-- What the row holds is not a secret. The id is the token's jti, which is useless without
-- the HMAC signature over it; the signing key is the secret, and it never touches the
-- database. The worst a compromised query can do here is revoke sessions (sign people
-- out) or un-revoke one it would also need the signed token to use. Access is bounded by
-- the grant below instead.
-- ---------------------------------------------------------------------------

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    issued_at   TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID
);

-- Revoke-all on reuse, and the per-user pruning done on every issue, both filter by user.
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

-- V17's ALTER DEFAULT PRIVILEGES already grants this to linkup_app, because Flyway runs
-- as the owner. Named anyway, in V17's spirit: a table the app role can write should be a
-- visible decision, not only a default. No TRUNCATE, as everywhere else.
GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_tokens TO linkup_app;

COMMENT ON TABLE refresh_tokens IS
    'One row per issued refresh token (id = JWT jti). Rotation and revocation state only; the token itself is never stored. No RLS: only read from unauthenticated /auth endpoints, see V28.';
