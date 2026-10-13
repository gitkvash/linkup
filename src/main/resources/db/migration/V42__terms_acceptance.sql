-- Proof that a person agreed to the Terms of Service and Privacy Policy, and to which
-- version: the two columns are the whole record, written in the same INSERT that creates
-- the account (register, first Google sign-in) or by PUT /api/v1/users/me/terms.
--
-- Both stay NULL for every account that existed before this migration. That is the honest
-- state - those people never agreed to anything the app can show - and it is also what makes
-- the client put the acceptance screen in front of them on their next launch: "accepted
-- version differs from the current one" covers a first acceptance and a later revision of
-- the documents with one rule.
--
-- Only the latest acceptance is kept, not a history. Deleting the account deletes the
-- record with it (app_delete_current_account removes the users row), which is what an
-- erasure request asks for.
ALTER TABLE users ADD COLUMN terms_version VARCHAR(20);
ALTER TABLE users ADD COLUMN terms_accepted_at TIMESTAMPTZ;

ALTER TABLE users ADD CONSTRAINT chk_users_terms_pair
    CHECK ((terms_version IS NULL) = (terms_accepted_at IS NULL));
