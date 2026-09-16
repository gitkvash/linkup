-- Gives ActivityVisibility.GROUP something to mean.
--
-- The enum has had a GROUP constant since V2, but activities never had a column saying
-- *which* group, so nothing could ever match on it. A plan created as GROUP was visible to
-- its creator and its participants and nobody else - the same as PRIVATE, but labelled as
-- though it were shared. The client has been hiding the option rather than offering a
-- choice that silently did the wrong thing.

ALTER TABLE activities ADD COLUMN group_id UUID;

-- No cascade: there is no delete-group endpoint, and if one is added later, silently
-- deleting or orphaning every plan made for that group should be a deliberate decision
-- rather than something the schema does quietly. RESTRICT makes it surface.
ALTER TABLE activities
    ADD CONSTRAINT fk_activities_group
    FOREIGN KEY (group_id) REFERENCES groups (group_id);

-- Partial: only GROUP activities carry one, and they are a minority.
CREATE INDEX idx_activities_group ON activities (group_id) WHERE group_id IS NOT NULL;

-- Any GROUP row predating this column has no group to point at. In practice those were
-- creator-and-participants-only, which is what PRIVATE says, so relabel rather than invent
-- a group for them. Runs before the constraint below, which would otherwise reject them.
UPDATE activities SET visibility = 'PRIVATE' WHERE visibility = 'GROUP' AND group_id IS NULL;

-- The two must agree in both directions: a GROUP activity without a group is invisible to
-- everyone it was meant for, and a group id on a PUBLIC activity is a claim the visibility
-- rules do not honour. Either way the row would not mean what it appears to.
ALTER TABLE activities
    ADD CONSTRAINT chk_activities_group_visibility
    CHECK ((visibility = 'GROUP') = (group_id IS NOT NULL));


-- ---------------------------------------------------------------------------
-- Teach row-level security about the new branch.
--
-- app_can_see_activity() is what the locations and participants policies delegate to, so
-- they pick this up without being touched. The activities policy spells the rule out
-- against its own columns (see V15) and has to be re-stated.
-- ---------------------------------------------------------------------------

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
              OR (a.visibility = 'GROUP' AND app_is_group_member(a.group_id, p_user))
          )
    )
$$;

DROP POLICY IF EXISTS activity_select_policy ON activities;
CREATE POLICY activity_select_policy ON activities FOR SELECT USING (
    creator_id = app_current_user()
    OR visibility = 'PUBLIC'
    OR app_is_participant(activity_id, app_current_user())
    OR (visibility = 'FRIENDS' AND app_are_friends(creator_id, app_current_user()))
    OR (visibility = 'GROUP' AND app_is_group_member(group_id, app_current_user()))
);
