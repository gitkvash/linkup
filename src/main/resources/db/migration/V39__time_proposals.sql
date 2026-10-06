-- "Suggest another time": someone in a plan (invited, going or declined) proposes a different
-- start for it, and the host answers. Accepting moves the plan for everyone and puts the
-- proposer in it - the point of the feature is that "I can't do 6, but I could do 8" ends
-- with that person coming, not with a chat message nobody acts on.
--
-- A proposal is a row of its own rather than a column on participants: a plan can have several
-- at once (one per person at most while pending), and the answered ones are the record of what
-- was asked. They are never deleted by the app - withdrawing or answering only changes status -
-- so the application role gets no DELETE (see the grant below).
--
-- Status: PENDING until answered. ACCEPTED / DECLINED by the host, WITHDRAWN by the proposer or
-- by proposing again, SUPERSEDED when another proposal was accepted and the plan moved out from
-- under this one.
--
-- Both foreign keys cascade. Deleting a plan removes its proposals with it, and so does deleting
-- an account (the same reasoning as refresh_tokens, V28): the proposals a deleted user made mean
-- nothing without them, so app_delete_current_account() (V33) needs no new line.

CREATE TABLE time_proposals (
    proposal_id         UUID PRIMARY KEY,
    activity_id         UUID        NOT NULL REFERENCES activities (activity_id) ON DELETE CASCADE,
    proposer_id         UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    proposed_start_time TIMESTAMPTZ NOT NULL,
    proposed_end_time   TIMESTAMPTZ,
    message             VARCHAR(200),
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    CONSTRAINT chk_time_proposals_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'WITHDRAWN', 'SUPERSEDED')),
    CONSTRAINT chk_time_proposals_window
        CHECK (proposed_end_time IS NULL OR proposed_end_time > proposed_start_time)
);

-- At most one open suggestion per person per plan: suggesting again replaces it.
CREATE UNIQUE INDEX uq_time_proposals_one_pending
    ON time_proposals (activity_id, proposer_id) WHERE status = 'PENDING';

CREATE INDEX idx_time_proposals_activity ON time_proposals (activity_id);

ALTER TABLE time_proposals ENABLE ROW LEVEL SECURITY;

-- The proposer sees their own; the host sees everyone's. Nobody else - another guest has no
-- business seeing what a friend asked the host for.
CREATE POLICY time_proposals_select_policy ON time_proposals FOR SELECT USING (
    proposer_id = app_current_user()
    OR app_activity_creator(activity_id) = app_current_user()
);

-- Only as yourself, and only into a plan you have a participants row in.
CREATE POLICY time_proposals_insert_policy ON time_proposals FOR INSERT WITH CHECK (
    proposer_id = app_current_user()
    AND app_is_participant(activity_id, app_current_user())
);

-- The proposer withdraws; the host answers.
CREATE POLICY time_proposals_update_policy ON time_proposals FOR UPDATE USING (
    proposer_id = app_current_user()
    OR app_activity_creator(activity_id) = app_current_user()
);

-- V17 grants table by table on purpose. No DELETE: the cascades above run as the table owner.
GRANT SELECT, INSERT, UPDATE ON time_proposals TO linkup_app;
