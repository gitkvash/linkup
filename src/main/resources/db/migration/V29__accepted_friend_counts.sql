-- Accepted-friend counts that are correct on a request connection.
--
-- The feed decides whether a creator is an "influencer" (fan-out-on-read) by counting
-- their accepted friends. The write side does that on a background thread as the owner,
-- which RLS exempts; the read side does it while serving a feed page, as linkup_app,
-- where friendships_select_policy (V16) only shows rows involving the caller. So on the
-- read side every friend counted as having exactly one friend - the caller - no one was
-- ever an influencer, and an influencer's plans, which the write side had declined to
-- fan out, reached nobody.
--
-- SECURITY DEFINER, like the V15 helpers: it counts as the owner, so the policy doesn't
-- apply inside it. What it exposes is deliberately only (user id, count) for ids the
-- caller already has - never who the friends are. A friend count is not otherwise
-- visible through the API, but it says nothing about any particular relationship, and
-- the feed cannot work without it.
--
-- Search path pinned for the reason given in V15: a SECURITY DEFINER function that
-- resolves `friendships` through the caller's search_path could be pointed at a table
-- they created.

CREATE OR REPLACE FUNCTION app_accepted_friend_counts(p_users uuid[])
    RETURNS TABLE (user_id uuid, friend_count bigint)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT counted.uid, count(*)
    FROM (
        SELECT f.user_a_id AS uid FROM friendships f
         WHERE f.status = 'ACCEPTED' AND f.user_a_id = ANY (p_users)
        UNION ALL
        SELECT f.user_b_id AS uid FROM friendships f
         WHERE f.status = 'ACCEPTED' AND f.user_b_id = ANY (p_users)
    ) counted
    GROUP BY counted.uid
$$;

COMMENT ON FUNCTION app_accepted_friend_counts(uuid[]) IS
    'Accepted-friend count per given user id, bypassing friendships RLS. Exposes counts only, never the friends themselves. Users with no accepted friends are omitted.';

-- EXECUTE defaults to PUBLIC for a new function. Only the application role needs it.
REVOKE ALL ON FUNCTION app_accepted_friend_counts(uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_accepted_friend_counts(uuid[]) TO linkup_app;
