CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY,
    recipient_user_id UUID NOT NULL REFERENCES users(user_id),
    type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ
);

CREATE INDEX idx_notifications_recipient ON notifications(recipient_user_id);

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

CREATE POLICY notifications_select_policy ON notifications FOR SELECT USING (
    recipient_user_id = current_setting('app.current_user_id', true)::uuid
);

-- Inserts are only ever performed by trusted server-side code (the notification event
-- listener), never directly by an end user via an exposed endpoint, and the recipient is
-- typically someone other than the request's current_user_id (e.g. an activity invitee).
-- So the insert check is intentionally permissive; SELECT/UPDATE above are the real
-- boundary protecting a user's own notifications.
CREATE POLICY notifications_insert_policy ON notifications FOR INSERT WITH CHECK (true);

CREATE POLICY notifications_update_policy ON notifications FOR UPDATE USING (
    recipient_user_id = current_setting('app.current_user_id', true)::uuid
);
