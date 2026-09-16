-- Index-only. Nothing here reads or rewrites a row, so it is safe to apply unattended.

-- "Activities I joined / was invited to" filters participants by user AND status
-- (ParticipantRepository.findActivityIdsByUserAndStatus, one of the three feed tabs).
-- Only idx_participants_user existed, so Postgres had to read every row for the user and
-- recheck status - the same shape V10 already fixed for activities and friendships.
CREATE INDEX IF NOT EXISTS idx_participants_user_status
    ON participants (user_id, status);

-- Superseded by the composite above: (user_id) is its leading column, so every plan that
-- could use this one can use that one. Keeping both only costs write amplification on
-- every join/leave and extra pages to keep cached.
DROP INDEX IF EXISTS idx_participants_user;

-- Same story for the two pairs V10 added without retiring what they replaced:
-- idx_activities_creator is a prefix of idx_activities_creator_start, and the two
-- single-column friendship indexes are prefixes of the (user, status) pairs.
DROP INDEX IF EXISTS idx_activities_creator;
DROP INDEX IF EXISTS idx_friendships_user_a;
DROP INDEX IF EXISTS idx_friendships_user_b;

-- Every username lookup in UserRepository goes through LOWER(username), which can only
-- use ux_users_username_lower (V20). idx_users_username indexes the raw column and has
-- had no query able to use it since V20 - it is pure insert/update cost.
DROP INDEX IF EXISTS idx_users_username;
