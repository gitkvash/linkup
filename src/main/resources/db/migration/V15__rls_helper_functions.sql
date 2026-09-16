-- Groundwork for enforced row-level security (V16 adds the missing policies, V17 the
-- least-privilege role that is finally subject to them).
--
-- Two problems are solved here, before anything starts depending on RLS:
--
-- 1. `current_setting('app.current_user_id', true)::uuid` - the expression every existing
--    policy uses - THROWS on a recycled pooled connection. RESET on a custom GUC restores
--    its boot value, which is the empty string, not NULL, and '' cannot be cast to uuid:
--
--        SET   app.current_user_id = '...'  -> current_setting() = '<uuid>'
--        RESET app.current_user_id          -> current_setting() = ''      (NOT NULL)
--        SELECT ''::uuid                    -> ERROR: invalid input syntax for type uuid
--
--    Only a connection that never carried a user reads back NULL. Today this is invisible
--    because the application connects as a superuser and skips policy evaluation
--    entirely; the moment V17 lands, every anonymous request served by a connection that
--    previously carried a user would fail with a 500. app_current_user() below is
--    NULLIF-guarded and is the only place the GUC is read from now on.
--
-- 2. Policies that reference other RLS-protected tables recurse. `participants` needs to
--    ask "may I see this activity?", and the activities policy already asks "is the
--    viewer a participant?" - mutually recursive policies make Postgres raise
--    "infinite recursion detected in policy for relation". Every cross-table lookup
--    therefore goes through a SECURITY DEFINER function: it runs as the owner, so the
--    inner read is not itself policy-checked, and the recursion is cut.
--
-- SECURITY DEFINER functions must pin search_path, otherwise a caller who can create
-- objects in an earlier schema can shadow the tables these functions read.

-- The authenticated caller, or NULL on a connection with no user context (background
-- work, or an anonymous request such as login). NULL never equals anything, so a NULL
-- caller simply matches no rows rather than erroring.
CREATE OR REPLACE FUNCTION app_current_user() RETURNS uuid
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, public
AS $$
    SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid
$$;

COMMENT ON FUNCTION app_current_user() IS
    'Authenticated user id from the app.current_user_id GUC, or NULL. NULLIF-guarded: RESET leaves an empty string, which is not castable to uuid.';

CREATE OR REPLACE FUNCTION app_are_friends(p_left uuid, p_right uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT p_left IS NOT NULL AND p_right IS NOT NULL AND EXISTS (
        SELECT 1 FROM friendships f
        WHERE f.status = 'ACCEPTED'
          AND ((f.user_a_id = p_left  AND f.user_b_id = p_right)
            OR (f.user_a_id = p_right AND f.user_b_id = p_left))
    )
$$;

CREATE OR REPLACE FUNCTION app_is_participant(p_activity uuid, p_user uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT p_user IS NOT NULL AND EXISTS (
        SELECT 1 FROM participants p
        WHERE p.activity_id = p_activity AND p.user_id = p_user
    )
$$;

-- Who owns an activity. Used by the child tables (locations, participants) to decide who
-- may write rows that hang off it.
CREATE OR REPLACE FUNCTION app_activity_creator(p_activity uuid) RETURNS uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT a.creator_id FROM activities a WHERE a.activity_id = p_activity
$$;

-- The single definition of "this activity is visible to this viewer". Deliberately the
-- same rule as ActivityVisibilitySql.VISIBLE_TO_VIEWER on the Java side - the two are
-- belt and braces, and they must not disagree, or legitimate rows silently vanish.
-- GROUP visibility is not handled here for the same reason it is not handled there:
-- activities has no group_id yet, so a GROUP activity is creator-only.
CREATE OR REPLACE FUNCTION app_can_see_activity(p_activity uuid, p_user uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT EXISTS (
        SELECT 1 FROM activities a
        WHERE a.activity_id = p_activity
          AND (
              a.creator_id = p_user
              OR a.visibility = 'PUBLIC'
              OR app_is_participant(a.activity_id, p_user)
              OR (a.visibility = 'FRIENDS' AND app_are_friends(a.creator_id, p_user))
          )
    )
$$;

CREATE OR REPLACE FUNCTION app_is_group_member(p_group uuid, p_user uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT p_user IS NOT NULL AND EXISTS (
        SELECT 1 FROM group_members gm
        WHERE gm.group_id = p_group AND gm.user_id = p_user
    )
$$;

CREATE OR REPLACE FUNCTION app_is_group_owner(p_group uuid, p_user uuid) RETURNS boolean
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
    SELECT p_user IS NOT NULL AND EXISTS (
        SELECT 1 FROM groups g
        WHERE g.group_id = p_group AND g.owner_id = p_user
    )
$$;


-- ---------------------------------------------------------------------------
-- Re-point the policies that already exist at the helpers above.
--
-- The activities rule is spelled out against the row's own columns rather than
-- delegating to app_can_see_activity(): on its own table that would re-read the same row
-- through a function for every row scanned. The cross-table parts still go through the
-- SECURITY DEFINER helpers, so there is no recursion.
-- ---------------------------------------------------------------------------

DROP POLICY IF EXISTS activity_select_policy ON activities;
CREATE POLICY activity_select_policy ON activities FOR SELECT USING (
    creator_id = app_current_user()
    OR visibility = 'PUBLIC'
    OR app_is_participant(activity_id, app_current_user())
    OR (visibility = 'FRIENDS' AND app_are_friends(creator_id, app_current_user()))
);

DROP POLICY IF EXISTS activity_insert_policy ON activities;
CREATE POLICY activity_insert_policy ON activities FOR INSERT WITH CHECK (
    creator_id = app_current_user()
);

DROP POLICY IF EXISTS activity_update_policy ON activities;
CREATE POLICY activity_update_policy ON activities FOR UPDATE USING (
    creator_id = app_current_user()
);

DROP POLICY IF EXISTS activity_delete_policy ON activities;
CREATE POLICY activity_delete_policy ON activities FOR DELETE USING (
    creator_id = app_current_user()
);

DROP POLICY IF EXISTS notifications_select_policy ON notifications;
CREATE POLICY notifications_select_policy ON notifications FOR SELECT USING (
    recipient_user_id = app_current_user()
);

-- Tightened from WITH CHECK (true). V7 left it open because notifications are written for
-- someone other than the caller, and there was no way to distinguish trusted server-side
-- work from a request. There is now: the dispatcher runs on a background thread, which
-- V17 routes to the owner connection and which is exempt from policies. Nothing on a
-- request thread inserts notifications, so the permissive escape hatch can go.
DROP POLICY IF EXISTS notifications_insert_policy ON notifications;
CREATE POLICY notifications_insert_policy ON notifications FOR INSERT WITH CHECK (
    recipient_user_id = app_current_user()
);

DROP POLICY IF EXISTS notifications_update_policy ON notifications;
CREATE POLICY notifications_update_policy ON notifications FOR UPDATE USING (
    recipient_user_id = app_current_user()
);
