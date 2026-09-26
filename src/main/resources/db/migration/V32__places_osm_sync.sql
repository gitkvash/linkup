-- Places kept in step with OpenStreetMap, instead of written out by hand in V31.
--
-- PlaceSyncJob asks the Overpass API for the named lakes, parks, malls, arenas and landmarks
-- in each configured area (weekly by default), and upserts them here keyed on osm_ref. The
-- 30 rows V31 seeded stay as they are: each has a radius judged by hand and a shorter name
-- than OSM's ("Dinamo Arena", not "Dinamo Arena named after Boris Paichadze"), so the sync
-- treats them as the curated source and never writes them.
--
-- Curation now means editing the odd row rather than writing every one:
--   * hide a place:           UPDATE places SET hidden = true WHERE osm_ref = 'way/123';
--   * keep your own edits:    UPDATE places SET source = 'CURATED', name = ..., radius_m = ...
--                             WHERE osm_ref = 'way/123';
-- The sync never changes `hidden` or a CURATED row, so both survive every later run.

ALTER TABLE places
    ADD COLUMN source    varchar(10) NOT NULL DEFAULT 'CURATED' CHECK (source IN ('CURATED', 'OSM')),
    ADD COLUMN hidden    boolean     NOT NULL DEFAULT false,
    ADD COLUMN synced_at timestamptz;

COMMENT ON COLUMN places.source IS
    'CURATED: written by hand, never touched by the OSM sync. OSM: owned by the sync, which updates and deletes it.';
COMMENT ON COLUMN places.hidden IS
    'Kept off the map and not linked to plans. The sync never changes it, so hiding a place survives later syncs.';
COMMENT ON COLUMN places.synced_at IS
    'When the OSM sync last saw this place. NULL for a CURATED row.';

-- When the last sync finished, so an instance started mid-week (or two at once, during a deploy)
-- knows whether one is due. A single row: the job also locks it FOR UPDATE while it writes, so
-- two instances can't both apply a sync.
CREATE TABLE place_sync (
    id              smallint PRIMARY KEY CHECK (id = 1),
    last_success_at timestamptz
);

INSERT INTO place_sync (id, last_success_at) VALUES (1, NULL);

-- Background bookkeeping, written only as the owner. V17's default privileges would hand it
-- to the request role.
REVOKE ALL ON place_sync FROM linkup_app;

-- A hidden place links nothing. Otherwise identical to V31's.
CREATE OR REPLACE FUNCTION app_place_for_point(p_point geometry)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, public
AS $$
    SELECT p.place_id
    FROM places p
    WHERE p_point IS NOT NULL
      AND NOT p.hidden
      AND ST_DWithin(p.geom_point::geography, p_point::geography, p.radius_m)
    ORDER BY p.radius_m, ST_Distance(p.geom_point::geography, p_point::geography)
    LIMIT 1
$$;
