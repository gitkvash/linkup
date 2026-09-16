-- What kind of activity this is (walking, cycling, climbing, ...), separate from
-- activity_type (which is about time structure - casual plan vs. specific event, not
-- category). GENERAL is the fallback for every plan created before this column existed
-- and the default when a client doesn't send one.

ALTER TABLE activities ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'GENERAL';
