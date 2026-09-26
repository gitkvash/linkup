-- What the friend profile and the "Your stats" screen need that the schema never kept.
--
-- 1. When a friendship began. The pair's row has had no timestamp since V1, so "friends
--    since" and "new friends this year" had nothing to read. Rows accepted before this
--    migration stay NULL: the date is genuinely unknown, and the client leaves the line
--    out rather than inventing one.
--
-- 2. Muting. "Stay friends, hide them from Feed" is one person's choice about the
--    other, so it is directional, but it lives and dies with the friendship: unfriending
--    or blocking deletes or replaces the row, and a mute has nothing left to mean. Two
--    flags on the pair's one row rather than a table of their own, which also keeps
--    app_delete_current_account() (V33) whole - it already deletes every friendship the
--    account is in, and a new user-referencing table would have to be taught to it.
--    Row-level security cannot say "only your own flag" (policies are row-level), so
--    SocialGraphService is what keeps each party to their own; either may already
--    update the row under friendships_update_policy (V16).
ALTER TABLE friendships ADD COLUMN accepted_at TIMESTAMPTZ;
ALTER TABLE friendships ADD COLUMN a_muted_b BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE friendships ADD COLUMN b_muted_a BOOLEAN NOT NULL DEFAULT false;


-- ---------------------------------------------------------------------------
-- Another person's headline counts: friends, hosted plans, groups.
--
-- None of the three is readable on a request connection. friendships and group_members
-- only show rows involving the caller (V16), and activities only the plans the caller
-- may see - so counted directly, every friend would have one friend (the caller), the
-- groups the two share, and the plans the caller happens to be allowed into. The same
-- trap V29 fixed for the feed, and the same way out: a SECURITY DEFINER function that
-- counts as the owner and hands back numbers only, never rows.
--
-- "Hosted" is plans they created that were started and not cancelled - two stored facts
-- (V25's started_at, V34's cancelled_at) rather than the derived lifecycle status: a plan
-- nobody started didn't happen, a host can still cancel one they started (and cancelling
-- wins in ActivityStatusResolver), and whether a started one has finished yet doesn't
-- change who hosted it.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_user_profile_counts(p_user uuid)
    RETURNS TABLE (friend_count bigint, hosted_count bigint, group_count bigint)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT
        (SELECT count(*) FROM friendships f
          WHERE f.status = 'ACCEPTED' AND (f.user_a_id = p_user OR f.user_b_id = p_user)),
        (SELECT count(*) FROM activities a
          WHERE a.creator_id = p_user AND a.started_at IS NOT NULL AND a.cancelled_at IS NULL),
        (SELECT count(*) FROM group_members gm WHERE gm.user_id = p_user)
$$;

COMMENT ON FUNCTION app_user_profile_counts(uuid) IS
    'Friend, hosted-plan and group counts for any user, bypassing RLS. Counts only, never the rows behind them. See V35.';

REVOKE ALL ON FUNCTION app_user_profile_counts(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_user_profile_counts(uuid) TO linkup_app;


-- ---------------------------------------------------------------------------
-- Mutual friends: the caller's own friends who are also friends with p_other.
--
-- The caller can read their own half of that (their friendships) but not the other
-- person's, for the reason above. What comes back is always a subset of the caller's
-- own friend list, so the only thing it reveals about p_other is which of the caller's
-- friends they know - the ordinary meaning of "mutual friends". The caller is taken
-- from app_current_user(), never a parameter, so no one can ask on anyone else's
-- behalf; a connection with no user gets nothing.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_mutual_friends(p_other uuid)
    RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    WITH mine AS (
        SELECT CASE WHEN f.user_a_id = app_current_user() THEN f.user_b_id ELSE f.user_a_id END AS uid
        FROM friendships f
        WHERE f.status = 'ACCEPTED'
          AND (f.user_a_id = app_current_user() OR f.user_b_id = app_current_user())
    ), theirs AS (
        SELECT CASE WHEN f.user_a_id = p_other THEN f.user_b_id ELSE f.user_a_id END AS uid
        FROM friendships f
        WHERE f.status = 'ACCEPTED'
          AND (f.user_a_id = p_other OR f.user_b_id = p_other)
    )
    SELECT mine.uid FROM mine JOIN theirs ON theirs.uid = mine.uid
    WHERE app_current_user() IS NOT NULL AND mine.uid <> p_other
$$;

COMMENT ON FUNCTION app_mutual_friends(uuid) IS
    'Accepted friends of the caller (app_current_user()) who are also accepted friends of p_other. Always a subset of the caller''s own friends. See V35.';

REVOKE ALL ON FUNCTION app_mutual_friends(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_mutual_friends(uuid) TO linkup_app;
