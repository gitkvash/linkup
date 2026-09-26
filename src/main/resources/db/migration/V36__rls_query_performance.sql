-- Makes the row-level policies cheap enough to read through, without changing who sees
-- what. Measured against 50k plans / 150k participants as linkup_app (the owner, which
-- RLS exempts, in brackets):
--
--   map clusters for one Tbilisi viewport   3,700 ms -> 37 ms   [5 ms]
--   a feed page's batch read                   75 ms -> 27 ms   [22 ms]
--   the reminder scan (twice a minute)        2 ms -> 0.04 ms, and no longer grows
--
-- 1. Child tables defer to the activities policy through a subquery.
--
-- locations_select_policy and participants_select_policy (V16) called
-- app_can_see_activity() per row. It is SECURITY DEFINER with a pinned search_path, so the
-- planner can never inline it: every location or participant row read - including the
-- participant_count subquery ActivityQueryRepository runs for every card - paid a separate
-- function execution, which itself re-read the activity and called three more helpers.
--
-- An EXISTS over activities says the same thing, because a subquery inside a policy is
-- itself subject to the policies of the table it reads: activity_select_policy applies to
-- it, and that policy is word for word the predicate app_can_see_activity() evaluates
-- (V18). It cannot recurse - activity_select_policy reads participants, friendships and
-- group_members only through SECURITY DEFINER helpers, which policies don't apply inside.
--
-- 2. app_current_user() is wrapped in a scalar subquery.
--
-- It carries a SET clause too, so it is not inlined either, and a bare call is evaluated
-- once per row per mention. (SELECT app_current_user()) is an InitPlan: once per query.
-- Same value either way - the GUC is session-level and set before any statement runs.
--
-- The write policies are left alone: they see one row per statement.

DROP POLICY IF EXISTS activity_select_policy ON activities;
CREATE POLICY activity_select_policy ON activities FOR SELECT USING (
    creator_id = (SELECT app_current_user())
    OR visibility = 'PUBLIC'
    OR app_is_participant(activity_id, (SELECT app_current_user()))
    OR (visibility = 'FRIENDS' AND app_are_friends(creator_id, (SELECT app_current_user())))
    OR (visibility = 'GROUP' AND app_is_group_member(group_id, (SELECT app_current_user())))
);

DROP POLICY IF EXISTS participants_select_policy ON participants;
CREATE POLICY participants_select_policy ON participants FOR SELECT USING (
    user_id = (SELECT app_current_user())
    OR EXISTS (SELECT 1 FROM activities a WHERE a.activity_id = participants.activity_id)
);

DROP POLICY IF EXISTS locations_select_policy ON locations;
CREATE POLICY locations_select_policy ON locations FOR SELECT USING (
    EXISTS (SELECT 1 FROM activities a WHERE a.activity_id = locations.activity_id)
);

DROP POLICY IF EXISTS friendships_select_policy ON friendships;
CREATE POLICY friendships_select_policy ON friendships FOR SELECT USING (
    user_a_id = (SELECT app_current_user())
    OR user_b_id = (SELECT app_current_user())
);

DROP POLICY IF EXISTS notifications_select_policy ON notifications;
CREATE POLICY notifications_select_policy ON notifications FOR SELECT USING (
    recipient_user_id = (SELECT app_current_user())
);

-- 3. Viewport and text prefilters for the map.
--
-- PostGIS's && and ILIKE are not LEAKPROOF. Under RLS, Postgres evaluates a table's policy
-- before any non-leakproof condition on it - and so will not use one as an index
-- condition either. The map's bounding box therefore could not use the GiST index on
-- locations: every map pan scanned the whole table, ran the visibility policy on every
-- location ever created, and only then kept the ones in view. Its cost grew with the
-- table, not with the viewport.
--
-- These run as the owner, where the index is usable, and return only the ids of plans
-- the *caller* may see - app_can_see_activity() against app_current_user(), which comes
-- from the connection, not from an argument, so they cannot be asked about anyone else.
-- The query that uses them still reads activities and locations as linkup_app, so every
-- policy and ActivityVisibilitySql still apply on top; these only decide which rows are
-- worth checking. Neither filters on status: ActivityStatusSql.NOT_OVER stays in the Java
-- query, the one place that says it in SQL.
--
-- Search path pinned for the reason given in V15.

CREATE OR REPLACE FUNCTION app_visible_activities_in_bbox(
        p_min_lng float8, p_min_lat float8, p_max_lng float8, p_max_lat float8)
    RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    ROWS 200
AS $$
    SELECT l.activity_id
    FROM locations l
    WHERE l.geom_point && ST_MakeEnvelope(p_min_lng, p_min_lat, p_max_lng, p_max_lat, 4326)
      AND app_can_see_activity(l.activity_id, app_current_user())
$$;

COMMENT ON FUNCTION app_visible_activities_in_bbox(float8, float8, float8, float8) IS
    'Ids of plans located in the box that the connection''s user may see. Index-usable prefilter for the map; see V36.';

-- The pattern is a LIKE pattern, already escaped by the caller (ActivityMapRepository).
CREATE OR REPLACE FUNCTION app_visible_activities_matching(p_pattern text)
    RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    ROWS 200
AS $$
    SELECT a.activity_id
    FROM activities a
    JOIN locations l ON l.activity_id = a.activity_id
    WHERE (a.title ILIKE p_pattern OR l.address_text ILIKE p_pattern)
      AND app_can_see_activity(a.activity_id, app_current_user())
$$;

COMMENT ON FUNCTION app_visible_activities_matching(text) IS
    'Ids of located plans whose title or address matches the ILIKE pattern and that the connection''s user may see. Prefilter for map search; see V36.';

REVOKE ALL ON FUNCTION app_visible_activities_in_bbox(float8, float8, float8, float8) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_visible_activities_matching(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_visible_activities_in_bbox(float8, float8, float8, float8) TO linkup_app;
GRANT EXECUTE ON FUNCTION app_visible_activities_matching(text) TO linkup_app;

-- 4. The reminder scan's repeating plans.
--
-- ActivityRepository.findReminderCandidates runs twice a minute. Its only indexable bound
-- was start_time <= :to, so each run walked every plan that had ever started. It now
-- bounds one-offs on both sides (idx_activities_start_time) and finds repeating plans
-- here - a stored start is a repeating plan's first occurrence, so those have no lower
-- bound to give.
CREATE INDEX idx_activities_repeating_start
    ON activities (start_time)
    WHERE repeat_freq IS NOT NULL AND cancelled_at IS NULL;
