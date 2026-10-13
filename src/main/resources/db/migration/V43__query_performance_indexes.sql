-- The read paths that still grew with the whole history rather than with what is on screen,
-- and the indexes that were missing or doubled up. Nothing here changes who sees what.
-- Measured against 50k plans (6k of them possibly not over) / 150k participants /
-- 1,000 places as linkup_app:
--
--   map clusters, whole-city viewport          4,100 ms -> 750 ms
--   map clusters, one neighbourhood              160 ms ->  35 ms
--   map search, common word ("vake")           3,900 ms -> 150 ms
--   map search, rare word                      4,100 ms ->   7 ms
--
-- What is left on the map is one visibility check per plan that may still be on.
--
-- 1. Only plans that might still be on are worth a visibility check.
--
-- app_visible_activities_in_bbox and app_visible_activities_matching (V36) ran
-- app_can_see_activity() - SECURITY DEFINER, so never inlined, and three more helper calls
-- inside - on every plan ever located in the box or matching the text, years of finished
-- plans included, and the Java query then threw the finished ones away with
-- ActivityStatusSql.NOT_OVER. Worse, the matching function's planner pushed the visibility
-- call down to the activities scan, below the text match: every search paid it on every
-- plan in the table.
--
-- app_activity_may_be_open() is a cheap superset of NOT_OVER, said with nothing but column
-- tests so that it can run first and use indexes:
--
--   NOT_OVER                                         this
--   cancelled_at IS NULL AND (                       cancelled_at IS NULL AND (
--     repeating and the rule hasn't run out            repeat_freq IS NOT NULL
--     started, not ended, inside its window            started_at IS NOT NULL AND ended_at IS NULL
--     not started, now < start + 2 hours or 1 day      start_time > now() - interval '2 days')
--
-- Each NOT_OVER branch implies the one beside it. The last allows two days, not one, so
-- that no time zone or DST arithmetic on '1 day' can make it the narrower of the two. It is
-- a prefilter only: NOT_OVER stays in the Java queries and is still what decides, so a
-- plan this keeps that is in fact over costs one wasted visibility check, never a pin.
-- The reverse is the danger: if NOT_OVER is ever widened past this, plans silently vanish
-- from the map. ActivityStatusSql.NOT_OVER's Javadoc points here for that reason.
--
-- Written as a SQL-standard body with no SET clause, so the planner inlines it: callers get
-- the bare column tests, which idx_activities_start_time, idx_activities_repeating_start
-- (V36) and idx_activities_open_started below can answer with a BitmapOr. All of them are
-- leakproof, so under RLS they may run before the policy and serve as index conditions.

CREATE OR REPLACE FUNCTION app_activity_may_be_open(
        p_cancelled_at timestamptz, p_repeat_freq varchar, p_started_at timestamptz,
        p_ended_at timestamptz, p_start_time timestamptz)
    RETURNS boolean
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
RETURN p_cancelled_at IS NULL
   AND (p_repeat_freq IS NOT NULL
        OR (p_started_at IS NOT NULL AND p_ended_at IS NULL)
        OR p_start_time > now() - interval '2 days');

COMMENT ON FUNCTION app_activity_may_be_open(timestamptz, varchar, timestamptz, timestamptz, timestamptz) IS
    'Index-usable superset of ActivityStatusSql.NOT_OVER: false only for a plan that is certainly over. A prefilter, never the rule; see V43.';

-- A started plan has no upper bound on start_time to give: the host may start a plan
-- whenever they like and it stays on until they end it.
CREATE INDEX idx_activities_open_started
    ON activities (started_at)
    WHERE started_at IS NOT NULL AND ended_at IS NULL AND cancelled_at IS NULL;

-- The candidates are gathered in a MATERIALIZED CTE and only then checked for visibility,
-- so the planner cannot push app_can_see_activity() back below the cheap filters. The
-- rest is as V36 had it: the caller comes from the connection, and the query that uses
-- these still reads as linkup_app under every policy.

CREATE OR REPLACE FUNCTION app_visible_activities_in_bbox(
        p_min_lng float8, p_min_lat float8, p_max_lng float8, p_max_lat float8)
    RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    ROWS 200
AS $$
    WITH candidates AS MATERIALIZED (
        SELECT a.activity_id
        FROM locations l
        JOIN activities a ON a.activity_id = l.activity_id
        WHERE l.geom_point && ST_MakeEnvelope(p_min_lng, p_min_lat, p_max_lng, p_max_lat, 4326)
          AND app_activity_may_be_open(a.cancelled_at, a.repeat_freq, a.started_at, a.ended_at, a.start_time)
    )
    SELECT c.activity_id
    FROM candidates c
    WHERE app_can_see_activity(c.activity_id, app_current_user())
$$;

COMMENT ON FUNCTION app_visible_activities_in_bbox(float8, float8, float8, float8) IS
    'Ids of plans located in the box, possibly not over, that the connection''s user may see. Index-usable prefilter for the map; see V36 and V43.';

-- 2. Search by text through trigram indexes.
--
-- A leading-% ILIKE can use no btree, so search read every plan. pg_trgm's GIN indexes
-- answer '%foo%' for patterns of three characters or more; the planner falls back to the
-- open-plan indexes above for shorter ones. A title-or-address OR across two tables can use
-- neither index, so the match is a UNION of one branch per column.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_activities_title_trgm ON activities USING gin (title gin_trgm_ops);
CREATE INDEX idx_locations_address_trgm ON locations USING gin (address_text gin_trgm_ops);

-- The pattern is a LIKE pattern, already escaped by the caller (ActivityMapRepository).
CREATE OR REPLACE FUNCTION app_visible_activities_matching(p_pattern text)
    RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    ROWS 200
AS $$
    WITH candidates AS MATERIALIZED (
        SELECT a.activity_id
        FROM activities a
        JOIN locations l ON l.activity_id = a.activity_id
        WHERE a.title ILIKE p_pattern
          AND app_activity_may_be_open(a.cancelled_at, a.repeat_freq, a.started_at, a.ended_at, a.start_time)
        UNION
        SELECT a.activity_id
        FROM locations l
        JOIN activities a ON a.activity_id = l.activity_id
        WHERE l.address_text ILIKE p_pattern
          AND app_activity_may_be_open(a.cancelled_at, a.repeat_freq, a.started_at, a.ended_at, a.start_time)
    )
    SELECT c.activity_id
    FROM candidates c
    WHERE app_can_see_activity(c.activity_id, app_current_user())
$$;

COMMENT ON FUNCTION app_visible_activities_matching(text) IS
    'Ids of located plans, possibly not over, whose title or address matches the ILIKE pattern and that the connection''s user may see. Prefilter for map search; see V36 and V43.';

-- CREATE OR REPLACE keeps V36's grants; restated so this file reads on its own.
REVOKE ALL ON FUNCTION app_visible_activities_in_bbox(float8, float8, float8, float8) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_visible_activities_matching(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_visible_activities_in_bbox(float8, float8, float8, float8) TO linkup_app;
GRANT EXECUTE ON FUNCTION app_visible_activities_matching(text) TO linkup_app;

-- People search (UserRepository.searchByUsername) is LOWER(username) LIKE '%q%'.
-- users_select_policy is USING (true), so RLS does not stand between it and the index.
CREATE INDEX idx_users_username_trgm ON users USING gin (lower(username) gin_trgm_ops);

-- 3. Linking a location to a place.
--
-- app_place_for_point (V32) runs on every location written. Its ST_DWithin took each
-- place's own radius, which no index can use, so every save read every place - 150 of them
-- in Tbilisi after one OSM sync, and growing with each area added. No radius exceeds
-- 5,000 m (V31's CHECK), so a fixed 5,000 m test first loses nothing and can use an index
-- on the same geography expression. Partial, like the function: a hidden place links nothing.

CREATE INDEX idx_places_geog_visible
    ON places USING gist ((geom_point::geography))
    WHERE NOT hidden;

CREATE OR REPLACE FUNCTION app_place_for_point(p_point geometry)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, public
AS $$
    SELECT p.place_id
    FROM places p
    WHERE p_point IS NOT NULL
      AND NOT p.hidden
      AND ST_DWithin(p.geom_point::geography, p_point::geography, 5000)
      AND ST_DWithin(p.geom_point::geography, p_point::geography, p.radius_m)
    ORDER BY p.radius_m, ST_Distance(p.geom_point::geography, p_point::geography)
    LIMIT 1
$$;

-- 4. Foreign keys with no index on the referencing side. Deleting a user (V33) checks
-- both, and each check was a full scan.
CREATE INDEX idx_friendships_requested_by ON friendships (requested_by);
CREATE INDEX idx_time_proposals_proposer ON time_proposals (proposer_id);

-- 5. The read policies V36 left calling app_current_user() per row.
--
-- Same rewrite and same reason as V36 section 2: (SELECT app_current_user()) is evaluated
-- once per query. Who passes each policy is unchanged.

DROP POLICY IF EXISTS groups_select_policy ON groups;
CREATE POLICY groups_select_policy ON groups FOR SELECT USING (
    owner_id = (SELECT app_current_user())
    OR app_is_group_member(group_id, (SELECT app_current_user()))
);

DROP POLICY IF EXISTS group_members_select_policy ON group_members;
CREATE POLICY group_members_select_policy ON group_members FOR SELECT USING (
    app_is_group_member(group_id, (SELECT app_current_user()))
);

DROP POLICY IF EXISTS time_proposals_select_policy ON time_proposals;
CREATE POLICY time_proposals_select_policy ON time_proposals FOR SELECT USING (
    proposer_id = (SELECT app_current_user())
    OR app_activity_creator(activity_id) = (SELECT app_current_user())
);

DROP POLICY IF EXISTS device_tokens_select_policy ON device_tokens;
CREATE POLICY device_tokens_select_policy ON device_tokens FOR SELECT USING (
    user_id = (SELECT app_current_user())
);

-- 6. Indexes another index already covers.
--
-- Each of these is the leading column of a wider index or primary key, which answers the
-- same lookups; keeping them only cost every write.
--   idx_notifications_recipient (recipient_user_id)   -> idx_notifications_recipient_created
--   idx_group_members_group     (group_id)            -> group_members_pkey (group_id, user_id)
--   idx_device_tokens_user      (user_id)             -> device_tokens_pkey (user_id, fcm_token)
DROP INDEX IF EXISTS idx_notifications_recipient;
DROP INDEX IF EXISTS idx_group_members_group;
DROP INDEX IF EXISTS idx_device_tokens_user;

-- The notification list is read ORDER BY created_at DESC, notification_id DESC
-- (NotificationRepository). With the id in the index too, a page is read straight off it
-- with no sort.
DROP INDEX IF EXISTS idx_notifications_recipient_created;
CREATE INDEX idx_notifications_recipient_created
    ON notifications (recipient_user_id, created_at DESC, notification_id DESC);
