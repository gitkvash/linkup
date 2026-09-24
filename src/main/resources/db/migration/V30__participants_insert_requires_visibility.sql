-- Joining a plan through RLS alone now requires being able to see it.
--
-- V16's insert policy let anyone write a participants row for themselves on any activity
-- (user_id = app_current_user()). On its own that was a way in, not just a write: the
-- new row makes app_is_participant() true, and app_can_see_activity() - which the
-- activities, locations and participants SELECT policies all go through - then grants
-- the plan, its exact location and its guest list. So the database's backstop for a
-- PRIVATE plan was only as good as the Java check in front of the insert
-- (ActivityParticipationService.requireVisible); RLS added nothing.
--
-- The self-insert branch now also has to pass app_can_see_activity(), evaluated before
-- the row exists, so being a participant can't be what makes it pass. Every insert the
-- application makes still does (each was run as linkup_app against the development
-- database, inside a rolled-back transaction):
--
--   creator auto-join at creation   creator branch (and creator_id = caller); the
--                                   SECURITY DEFINER lookup sees the activity row
--                                   inserted earlier in the same transaction
--   host inviting friends           creator branch, unchanged
--   join a PUBLIC / FRIENDS / GROUP visible by visibility, the same rule as
--     plan (upsertStatus)           ActivityVisibilitySql.VISIBLE_TO_VIEWER
--   accept / decline an invitation  the INVITED row already exists, so the caller is a
--     (upsertStatus ON CONFLICT)    participant; INSERT ... ON CONFLICT checks the
--                                   proposed row against this policy even when it takes
--                                   the update path, which is why this case matters
--
-- A stranger inserting themselves into a PRIVATE plan is now refused
-- ("new row violates row-level security policy"). Background work is unaffected: it
-- runs as the owner, which RLS exempts.

DROP POLICY IF EXISTS participants_insert_policy ON participants;
CREATE POLICY participants_insert_policy ON participants FOR INSERT WITH CHECK (
    app_activity_creator(activity_id) = app_current_user()
    OR (
        user_id = app_current_user()
        AND app_can_see_activity(activity_id, app_current_user())
    )
);
