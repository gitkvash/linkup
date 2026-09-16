-- Makes the schema agree with the entities.
--
-- These columns are all declared nullable = false in JPA but were nullable in SQL.
-- Nothing caught the drift because ddl-auto: validate checks that tables and columns
-- exist and that types are compatible - it does not compare nullability. So the
-- guarantee the code relied on simply wasn't there.
--
-- Verified before writing this: zero rows have a null in any of these columns.

ALTER TABLE users      ALTER COLUMN username   SET NOT NULL;
ALTER TABLE groups     ALTER COLUMN owner_id   SET NOT NULL;
ALTER TABLE activities ALTER COLUMN creator_id SET NOT NULL;
ALTER TABLE locations  ALTER COLUMN activity_id SET NOT NULL;

-- Deliberately not locations.geom_point: it is genuinely optional (a text-created
-- plan has an address but no coordinates), and the entity has been corrected to
-- match rather than the column being tightened. See V11.
