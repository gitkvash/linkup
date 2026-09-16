-- Usernames become unique without regard to case.
--
-- They were unique on the exact string, so "alice" and "Alice" were two accounts that
-- could be registered independently - a ready-made impersonation of anyone whose name
-- has a letter in it - while login matched case-sensitively, so someone typing their own
-- name with the wrong shift key was told their password was incorrect.
--
-- The stored spelling is left alone: users keep the capitalisation they typed, and it is
-- only the comparison that ignores case. Lookups in IdentityService use
-- lower(username) = lower(?) so they match this index.

-- A bare CREATE UNIQUE INDEX on a table that already holds "alice" and "Alice" fails with
-- "could not create unique index ... Key (lower(username))=(alice) is duplicated", which
-- says nothing about which accounts to look at. Deciding what happens to a colliding pair
-- (rename which one? merge? delete?) is not something a migration may do silently to real
-- accounts, so this stops and names them instead.
DO $$
DECLARE
    colliding text;
BEGIN
    SELECT string_agg(name, ', ' ORDER BY name)
      INTO colliding
      FROM (SELECT lower(username) AS name
              FROM users
             GROUP BY lower(username)
            HAVING count(*) > 1) AS dupes;

    IF colliding IS NOT NULL THEN
        RAISE EXCEPTION
            'Usernames cannot be made case-insensitive yet: % exist in more than one casing. Rename or merge those accounts, then re-run this migration.',
            colliding;
    END IF;
END $$;

ALTER TABLE users DROP CONSTRAINT users_username_key;

CREATE UNIQUE INDEX ux_users_username_lower ON users (lower(username));
