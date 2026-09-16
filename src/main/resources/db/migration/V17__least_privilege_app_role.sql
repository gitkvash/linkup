-- The migration that finally makes row-level security do something.
--
-- Until now every policy in this schema has been decoration. The application connects as
-- `linkup`, which is both the table owner and - because it is POSTGRES_USER in the
-- postgis image - a superuser. Postgres exempts the owner from ENABLE ROW LEVEL SECURITY,
-- and exempts a superuser from policies unconditionally. Confirmed on this database:
--
--     SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user;
--     -->  t | t
--
-- This creates a second login role with no ownership, no superuser bit, no BYPASSRLS and
-- nothing but DML grants, and the application's request-serving datasource switches to
-- it. `linkup` keeps running Flyway and keeps serving the background datasource.
--
-- ---------------------------------------------------------------------------
-- On FORCE ROW LEVEL SECURITY, which is NOT applied here
--
-- FORCE only changes one thing: whether the *table owner* is subject to policies. It has
-- no effect on `linkup_app`, which is not the owner and is already fully subject to them.
-- Applying it would be actively harmful: the owner connection is exactly what background
-- work - notification fan-out writing rows for other users, feed fan-out reading a
-- creator's friend list - relies on to see past the policies. With FORCE, that work
-- would depend solely on `linkup` happening to be a superuser, and would break silently
-- (zero rows, no error) the day it stopped being one.
--
-- The alternative is a third role with explicit BYPASSRLS for background work, which
-- would let FORCE be applied safely. It is not used here because granting BYPASSRLS
-- requires true superuser, which managed Postgres (RDS, Cloud SQL) does not give you -
-- the setup would then work locally and be impossible to deploy. The guarantee is the
-- same either way: exactly one role can see everything, and it is only ever reached from
-- off-request threads.
-- ---------------------------------------------------------------------------
--
-- The password below comes from the `app_db_password` Flyway placeholder. It must not
-- contain a single quote. For anything real, set a throwaway value here and rotate it out
-- of band with ALTER ROLE - migration SQL is replayed into logs.

DO $$
BEGIN
    -- Roles are cluster-wide, not per-database, so this can already exist.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'linkup_app') THEN
        ALTER ROLE linkup_app WITH LOGIN PASSWORD '${app_db_password}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS INHERIT;
    ELSE
        CREATE ROLE linkup_app WITH LOGIN PASSWORD '${app_db_password}'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS INHERIT;
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO linkup_app', current_database());
END
$$;
GRANT USAGE ON SCHEMA public TO linkup_app;

-- No CREATE on the schema: the application role must not be able to add tables, and in
-- particular must not be able to create a table that shadows one the SECURITY DEFINER
-- helpers read. (Postgres 15 already revokes this from PUBLIC; explicit for older
-- clusters restored into place.)
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM linkup_app;

-- Every table listed by name rather than ALL TABLES IN SCHEMA, so that a table added
-- later is a deliberate decision instead of an accident. Note what is absent: no TRUNCATE
-- (it is not covered by DELETE and bypasses policies entirely), no REFERENCES, no
-- TRIGGER, and no flyway_schema_history.
GRANT SELECT, INSERT, UPDATE, DELETE ON
    users,
    friendships,
    groups,
    group_members,
    activities,
    locations,
    participants,
    notifications,
    device_tokens
TO linkup_app;

-- Modulith's event registry. Written from the request thread (the publication row is
-- created in the same transaction as the business write) so the application role needs it
-- even though nothing about it is user-scoped.
GRANT SELECT, INSERT, UPDATE, DELETE ON event_publication TO linkup_app;

-- PostGIS reference data. ST_Transform in the map query fails without it.
GRANT SELECT ON spatial_ref_sys TO linkup_app;
GRANT SELECT ON geometry_columns, geography_columns TO linkup_app;

-- EXECUTE is granted to PUBLIC by default for new functions; naming them makes the
-- dependency explicit and survives a cluster that has revoked the default.
GRANT EXECUTE ON FUNCTION
    app_current_user(),
    app_are_friends(uuid, uuid),
    app_is_participant(uuid, uuid),
    app_activity_creator(uuid),
    app_can_see_activity(uuid, uuid),
    app_is_group_member(uuid, uuid),
    app_is_group_owner(uuid, uuid)
TO linkup_app;

-- Anything a future migration creates while connected as `linkup` is usable by the
-- application without a follow-up grant. Without this, the first table added by V18 would
-- work in every test that runs as the owner and fail in production only.
DO $$
BEGIN
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO linkup_app', current_user);
    EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO linkup_app', current_user);
END
$$;

COMMENT ON ROLE linkup_app IS
    'Request-serving application role. No ownership, no superuser, no BYPASSRLS - every policy in this schema applies to it. Background work uses the owner connection instead.';
