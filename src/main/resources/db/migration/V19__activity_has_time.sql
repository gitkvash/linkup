-- Lets an activity carry a date without a specific hour and minute ("tomorrow", "on
-- July 3rd"), instead of forcing every plan to claim a precise clock time it doesn't
-- have. start_time still holds a real timestamp either way (midnight local when there's
-- no time) so every existing query keeps working; has_time just says whether that clock
-- portion means anything.

ALTER TABLE activities ADD COLUMN has_time BOOLEAN NOT NULL DEFAULT true;
