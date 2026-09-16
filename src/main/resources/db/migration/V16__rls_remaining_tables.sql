-- Policies for the seven tables that had none. Until now only `activities` and
-- `notifications` were covered, so `locations` - which holds the exact coordinates of
-- every private plan - was readable in full by any connection.
--
-- Still inert at the end of this migration: the application connects as the table owner,
-- which Postgres exempts. V17 is what makes these bite. Splitting it that way means a
-- failure here leaves a working database rather than a half-locked-down one.
--
-- Each policy mirrors an authorization rule that already exists in Java. That duplication
-- is the point - RLS is the backstop for a query that forgets its predicate, not the
-- primary control.

-- ---------------------------------------------------------------------------
-- users
--
-- SELECT is deliberately open. The product has a user directory and a username search,
-- and login has to find an account by username before any caller identity exists, so
-- there is no row-level rule to apply that the API does not already grant. What this does
-- buy is that a compromised query cannot UPDATE or DELETE somebody else's account.
--
-- password_hash is not protected by RLS (policies are row-level, not column-level) and
-- cannot be, while login runs on the same role. It is kept out of responses by
-- UserSummary, which is what the identity tests assert.
-- ---------------------------------------------------------------------------
ALTER TABLE users ENABLE ROW LEVEL SECURITY;

CREATE POLICY users_select_policy ON users FOR SELECT USING (true);

-- Registration runs before there is a caller, so it cannot be checked against one.
CREATE POLICY users_insert_policy ON users FOR INSERT WITH CHECK (true);

CREATE POLICY users_update_policy ON users FOR UPDATE USING (
    user_id = app_current_user()
);

CREATE POLICY users_delete_policy ON users FOR DELETE USING (
    user_id = app_current_user()
);

-- ---------------------------------------------------------------------------
-- friendships
--
-- Both directions, because the pair is stored canonically (V12's
-- CHECK (user_a_id < user_b_id), unsigned-comparison ordering) rather than twice.
-- ---------------------------------------------------------------------------
ALTER TABLE friendships ENABLE ROW LEVEL SECURITY;

CREATE POLICY friendships_select_policy ON friendships FOR SELECT USING (
    user_a_id = app_current_user() OR user_b_id = app_current_user()
);

-- You may only create a request you are part of, and only in your own name - requested_by
-- decides who is allowed to accept it.
CREATE POLICY friendships_insert_policy ON friendships FOR INSERT WITH CHECK (
    requested_by = app_current_user()
    AND (user_a_id = app_current_user() OR user_b_id = app_current_user())
);

CREATE POLICY friendships_update_policy ON friendships FOR UPDATE USING (
    user_a_id = app_current_user() OR user_b_id = app_current_user()
);

CREATE POLICY friendships_delete_policy ON friendships FOR DELETE USING (
    user_a_id = app_current_user() OR user_b_id = app_current_user()
);

-- ---------------------------------------------------------------------------
-- groups / group_members
--
-- Matches GroupService: any member may read, only the owner may change membership, and
-- a member may remove themselves.
-- ---------------------------------------------------------------------------
ALTER TABLE groups ENABLE ROW LEVEL SECURITY;

CREATE POLICY groups_select_policy ON groups FOR SELECT USING (
    owner_id = app_current_user() OR app_is_group_member(group_id, app_current_user())
);

CREATE POLICY groups_insert_policy ON groups FOR INSERT WITH CHECK (
    owner_id = app_current_user()
);

CREATE POLICY groups_update_policy ON groups FOR UPDATE USING (
    owner_id = app_current_user()
);

CREATE POLICY groups_delete_policy ON groups FOR DELETE USING (
    owner_id = app_current_user()
);

ALTER TABLE group_members ENABLE ROW LEVEL SECURITY;

CREATE POLICY group_members_select_policy ON group_members FOR SELECT USING (
    app_is_group_member(group_id, app_current_user())
);

-- createGroup() inserts the group and the owner's own membership in one transaction; the
-- SECURITY DEFINER lookup sees the uncommitted group row, so the owner check holds.
CREATE POLICY group_members_insert_policy ON group_members FOR INSERT WITH CHECK (
    app_is_group_owner(group_id, app_current_user())
);

CREATE POLICY group_members_delete_policy ON group_members FOR DELETE USING (
    app_is_group_owner(group_id, app_current_user()) OR user_id = app_current_user()
);

-- No UPDATE policy: joined_at is write-once, and a command with no policy is denied.

-- ---------------------------------------------------------------------------
-- participants
--
-- Reads follow the activity's own visibility, plus your own row wherever it is - an
-- invitation to a plan you cannot otherwise see still has to be listable, which is
-- exactly what /activities/invited does.
-- ---------------------------------------------------------------------------
ALTER TABLE participants ENABLE ROW LEVEL SECURITY;

CREATE POLICY participants_select_policy ON participants FOR SELECT USING (
    user_id = app_current_user()
    OR app_can_see_activity(activity_id, app_current_user())
);

-- Two writers: you, joining; and the creator, inviting.
CREATE POLICY participants_insert_policy ON participants FOR INSERT WITH CHECK (
    user_id = app_current_user()
    OR app_activity_creator(activity_id) = app_current_user()
);

CREATE POLICY participants_update_policy ON participants FOR UPDATE USING (
    user_id = app_current_user()
    OR app_activity_creator(activity_id) = app_current_user()
);

CREATE POLICY participants_delete_policy ON participants FOR DELETE USING (
    user_id = app_current_user()
    OR app_activity_creator(activity_id) = app_current_user()
);

-- ---------------------------------------------------------------------------
-- locations
--
-- The most valuable table to protect and the one that had nothing: it holds the exact
-- geometry of every private plan, and the map query reads it directly rather than through
-- activities.
-- ---------------------------------------------------------------------------
ALTER TABLE locations ENABLE ROW LEVEL SECURITY;

CREATE POLICY locations_select_policy ON locations FOR SELECT USING (
    app_can_see_activity(activity_id, app_current_user())
);

CREATE POLICY locations_insert_policy ON locations FOR INSERT WITH CHECK (
    app_activity_creator(activity_id) = app_current_user()
);

CREATE POLICY locations_update_policy ON locations FOR UPDATE USING (
    app_activity_creator(activity_id) = app_current_user()
);

CREATE POLICY locations_delete_policy ON locations FOR DELETE USING (
    app_activity_creator(activity_id) = app_current_user()
);

-- ---------------------------------------------------------------------------
-- device_tokens
-- ---------------------------------------------------------------------------
ALTER TABLE device_tokens ENABLE ROW LEVEL SECURITY;

CREATE POLICY device_tokens_select_policy ON device_tokens FOR SELECT USING (
    user_id = app_current_user()
);

CREATE POLICY device_tokens_insert_policy ON device_tokens FOR INSERT WITH CHECK (
    user_id = app_current_user()
);

CREATE POLICY device_tokens_update_policy ON device_tokens FOR UPDATE USING (
    user_id = app_current_user()
);

-- Deliberately not scoped to the owner. Registering a device is a delete-then-insert that
-- has to take the token away from whoever held it before - that is the whole point of
-- V10's unique index on fcm_token, and of the hijacking fix it came from. Scoping this to
-- user_id would turn "sign in as someone else on the same phone" into a 409 instead.
-- The exposure is bounded: you can only delete a token you already know, and losing it
-- costs the other device its pushes rather than leaking anything.
CREATE POLICY device_tokens_delete_policy ON device_tokens FOR DELETE USING (true);

-- ---------------------------------------------------------------------------
-- Deliberately left without RLS:
--
--   event_publication      Modulith's own registry. Rows are serialized events keyed by
--                          listener, with no user column to filter on, and the registry
--                          writes them from whichever thread published. Access is
--                          controlled by the grant in V17 instead.
--   flyway_schema_history  Not granted to the application role at all.
--   spatial_ref_sys        PostGIS reference data; ST_Transform needs to read it.
-- ---------------------------------------------------------------------------
