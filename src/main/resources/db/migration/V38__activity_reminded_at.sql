-- When the host last pressed "Remind everyone" on a plan, so the button can't be used to
-- spam the people in it: the next press is refused until a cooldown has passed.
--
-- Only the host's manual nudge writes this. The automatic "starts in 30 minutes" and
-- "starts now" notices are not throttled by it (they dedupe on their own key).
--
-- Nullable with no default and no foreign key, like cancelled_at (V34), so existing
-- inserts and app_delete_current_account() (V33) are unaffected.
ALTER TABLE activities ADD COLUMN reminded_at TIMESTAMPTZ;
