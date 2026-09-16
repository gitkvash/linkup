-- What a person can say about themselves beyond the handle they log in with.
--
-- username stays what it has always been: unique, case-insensitively, and how people find
-- each other. display_name is how they want to be read - not unique, not an identifier,
-- and null for every account that has never set one, which is why every read falls back
-- to username rather than storing a copy of it here.
ALTER TABLE users ADD COLUMN display_name VARCHAR(50);
ALTER TABLE users ADD COLUMN bio          VARCHAR(160);
