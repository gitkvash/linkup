-- Child rows follow their parent.
--
-- Every foreign key was NO ACTION, so deleting an activity or a group was impossible
-- without hand-deleting its dependents first - which is why the update/delete RLS
-- policies in V4 guard endpoints that don't exist yet.
--
-- Constraint names were read from the live schema, not assumed.

ALTER TABLE participants DROP CONSTRAINT participants_activity_id_fkey;
ALTER TABLE participants
    ADD CONSTRAINT participants_activity_id_fkey
    FOREIGN KEY (activity_id) REFERENCES activities (activity_id) ON DELETE CASCADE;

ALTER TABLE locations DROP CONSTRAINT locations_activity_id_fkey;
ALTER TABLE locations
    ADD CONSTRAINT locations_activity_id_fkey
    FOREIGN KEY (activity_id) REFERENCES activities (activity_id) ON DELETE CASCADE;

ALTER TABLE group_members DROP CONSTRAINT group_members_group_id_fkey;
ALTER TABLE group_members
    ADD CONSTRAINT group_members_group_id_fkey
    FOREIGN KEY (group_id) REFERENCES groups (group_id) ON DELETE CASCADE;

-- The user-referencing keys stay NO ACTION on purpose. Deleting a user cascades into
-- their activities, friendships and notifications - an irreversible fan-out that
-- should be an explicit, reviewed operation, not a side effect of one DELETE. There
-- is no delete-user endpoint, so nothing depends on it today.
