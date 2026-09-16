-- Additive only: new indexes, one new nullable column, one unique constraint that
-- current data already satisfies (verified: no fcm_token is shared across users).
-- Nothing here can fail on existing rows, so it is safe to apply unattended.

-- Feed and "my activities" both filter by creator and order by start_time; only two
-- separate single-column indexes existed, so neither query could use one index.
CREATE INDEX IF NOT EXISTS idx_activities_creator_start
    ON activities (creator_id, start_time DESC);

-- The friendship lookups all filter on status as well as the user, from either side.
CREATE INDEX IF NOT EXISTS idx_friendships_user_a_status ON friendships (user_a_id, status);
CREATE INDEX IF NOT EXISTS idx_friendships_user_b_status ON friendships (user_b_id, status);

-- Notifications are always read newest-first for one recipient.
CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created
    ON notifications (recipient_user_id, created_at DESC);

-- Unread counts read only the unread rows, so index just those.
CREATE INDEX IF NOT EXISTS idx_notifications_unread
    ON notifications (recipient_user_id) WHERE read_at IS NULL;

-- Participant lists are queried per activity and filtered by status (a JOINED-only
-- headcount, invitations pending a response).
CREATE INDEX IF NOT EXISTS idx_participants_activity_status
    ON participants (activity_id, status);

-- Modulith looks up incomplete publications by listener; without this the query is a
-- sequential scan over a table that only grows.
CREATE INDEX IF NOT EXISTS idx_event_publication_listener
    ON event_publication (listener_id, completion_date);

-- A device token identifies one physical device, so it must belong to exactly one
-- user. The primary key was (user_id, fcm_token), which let user A register a token
-- they had seen belonging to user B - and B's device would then receive A's pushes.
CREATE UNIQUE INDEX IF NOT EXISTS uq_device_tokens_fcm_token
    ON device_tokens (fcm_token);

-- Idempotency key for notification delivery. Modulith retries an incomplete
-- publication after a restart, which would otherwise insert the same notification
-- again. Nullable: rows without a natural identity simply opt out of deduplication,
-- and Postgres allows any number of NULLs under a unique index.
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(200);
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_dedupe_key
    ON notifications (dedupe_key);
