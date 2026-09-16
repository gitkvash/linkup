-- Pins the geometry column to Point/4326.
--
-- V2 declared it as bare GEOMETRY, so geometry_columns reported srid = 0 and any
-- value could be stored with any SRID. The map query calls
-- ST_Transform(geom_point, 3857) inside its clustering CTE, and ST_Transform raises
-- "Input geometry has unknown (0) SRID" - one bad row would have taken down the whole
-- map endpoint for every user, not just for that activity.
--
-- Verified before writing this: 2 rows, both already SRID 4326, none NULL. The
-- USING clause still normalises anything that slipped in with SRID 0 rather than
-- failing the migration.

UPDATE locations
   SET geom_point = ST_SetSRID(geom_point, 4326)
 WHERE geom_point IS NOT NULL AND ST_SRID(geom_point) = 0;

ALTER TABLE locations
    ALTER COLUMN geom_point TYPE geometry(Point, 4326)
    USING CASE
        WHEN geom_point IS NULL THEN NULL
        ELSE ST_SetSRID(ST_Force2D(geom_point), 4326)::geometry(Point, 4326)
    END;

-- geom_point stays nullable on purpose: a plan created from free text can name a
-- place ("Vake park") with no coordinates, since there is no geocoder. Readers must
-- filter on IS NOT NULL - JDBC's getDouble turns a SQL NULL into 0.0, which would
-- otherwise put the pin in the Gulf of Guinea.
