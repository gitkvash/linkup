-- Well-known places: the lakes, parks, malls and arenas people actually make plans at.
--
-- The geocoder (Stadia, from the app) answers "where is this text", and answers it badly
-- for exactly these: "City Mall" resolved to a street in Limerick, "Arena 2" to Palermo,
-- and "Lisi Lake" came fifth behind a village and two cafes. A place here is a named spot
-- the map draws on its own, and the thing a plan is *at* - which is what lets the map say
-- "4 plans at Lisi Lake this week" instead of four unrelated pins by the water.
--
-- Seeded from OpenStreetMap (ODbL - the app's map attribution already credits it): each
-- point is the centre OSM gives the feature, and name_ka is OSM's `name`. radius_m is how
-- far from that centre a plan still counts as being at the place - a lake is large, a mall
-- entrance is not - and is a judgement call per place rather than OSM data.

CREATE TABLE places (
    place_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       varchar(120) NOT NULL,
    name_ka    varchar(120),
    kind       varchar(20)  NOT NULL CHECK (kind IN ('LAKE', 'PARK', 'MALL', 'SPORTS', 'LANDMARK')),
    geom_point geometry(Point, 4326) NOT NULL,
    radius_m   integer NOT NULL CHECK (radius_m BETWEEN 20 AND 5000),
    osm_ref    varchar(40) UNIQUE
);

CREATE INDEX idx_places_geom_point ON places USING gist (geom_point);

COMMENT ON TABLE places IS
    'Curated named places the map draws and plans link to. Reference data: the application role reads it and never writes it.';

-- Reference data, not user data: every caller may read every row, so no RLS. V17's default
-- privileges hand a new table SELECT/INSERT/UPDATE/DELETE to linkup_app; a request has no
-- business adding or moving a landmark, so it keeps only SELECT.
REVOKE INSERT, UPDATE, DELETE ON places FROM linkup_app;
GRANT SELECT ON places TO linkup_app;

INSERT INTO places (name, name_ka, kind, geom_point, radius_m, osm_ref) VALUES
    -- Water
    ('Lisi Lake',                 'ლისის ტბა',                    'LAKE',     ST_SetSRID(ST_MakePoint(44.734537, 41.743854), 4326),  550, 'way/20841667'),
    ('Turtle Lake',               'კუს ტბა',                      'LAKE',     ST_SetSRID(ST_MakePoint(44.754436, 41.700356), 4326),  300, 'way/28043570'),
    ('Tbilisi Sea',               'თბილისის ზღვა',                'LAKE',     ST_SetSRID(ST_MakePoint(44.852484, 41.742031), 4326), 2000, 'relation/19423317'),
    -- Parks and gardens
    ('Vake Park',                 'ვაკის პარკი',                  'PARK',     ST_SetSRID(ST_MakePoint(44.747601, 41.708737), 4326),  450, 'relation/17233994'),
    ('Mtatsminda Park',           'მთაწმინდის პარკი',             'PARK',     ST_SetSRID(ST_MakePoint(44.786721, 41.694904), 4326),  300, 'relation/18003984'),
    ('Rike Park',                 'რიყის პარკი',                  'PARK',     ST_SetSRID(ST_MakePoint(44.810300, 41.693131), 4326),  300, 'relation/9868102'),
    ('Mziuri Park',               'მზიურის პარკი',                'PARK',     ST_SetSRID(ST_MakePoint(44.771616, 41.711684), 4326),  250, 'way/34747066'),
    ('Dedaena Park',              'დედაენის ბაღი',                'PARK',     ST_SetSRID(ST_MakePoint(44.804494, 41.700581), 4326),  200, 'way/174304310'),
    ('Mushtaidi Garden',          'მუშტაიდის ბაღი',               'PARK',     ST_SetSRID(ST_MakePoint(44.786378, 41.721911), 4326),  250, 'way/56660039'),
    ('National Botanical Garden', 'ეროვნული ბოტანიკური ბაღი',     'PARK',     ST_SetSRID(ST_MakePoint(44.803991, 41.685074), 4326),  500, 'relation/19630635'),
    ('Tbilisi Zoo',               'თბილისის ზოოპარკი',            'PARK',     ST_SetSRID(ST_MakePoint(44.776915, 41.713034), 4326),  250, 'way/104122415'),
    -- Malls
    ('City Mall Saburtalo',       'სითი მოლი საბურთალო',          'MALL',     ST_SetSRID(ST_MakePoint(44.737671, 41.723882), 4326),  150, 'relation/17841603'),
    ('City Mall Gldani',          'სითი მოლი გლდანი',             'MALL',     ST_SetSRID(ST_MakePoint(44.814266, 41.789793), 4326),  150, 'way/587717099'),
    ('Tbilisi Mall',              'თბილისი მოლი',                 'MALL',     ST_SetSRID(ST_MakePoint(44.772614, 41.816530), 4326),  200, 'way/167318119'),
    ('East Point',                'ისთ ფოინთი',                   'MALL',     ST_SetSRID(ST_MakePoint(44.898254, 41.689901), 4326),  200, 'way/371721697'),
    ('Galleria Tbilisi',          'გალერია თბილისი',              'MALL',     ST_SetSRID(ST_MakePoint(44.799870, 41.694520), 4326),  100, 'way/559446309'),
    ('Karvasla',                  'ქარვასლა',                     'MALL',     ST_SetSRID(ST_MakePoint(44.802454, 41.719109), 4326),  100, 'way/124320620'),
    ('Pixel',                     'პიკსელი',                      'MALL',     ST_SetSRID(ST_MakePoint(44.770159, 41.708881), 4326),   80, 'way/125337479'),
    -- Arenas and stadiums. Arena 1 and Arena 2 stand about 100m apart, hence the tight radii.
    ('Arena 2',                   'არენა II',                     'SPORTS',   ST_SetSRID(ST_MakePoint(44.740842, 41.717937), 4326),   60, 'way/34627045'),
    ('Arena 1',                   'არენა I',                      'SPORTS',   ST_SetSRID(ST_MakePoint(44.741997, 41.718265), 4326),   60, 'way/56125362'),
    ('Dinamo Arena',              'დინამო არენა',                 'SPORTS',   ST_SetSRID(ST_MakePoint(44.789794, 41.723018), 4326),  250, 'relation/17823425'),
    ('Mikheil Meskhi Stadium',    'მიხეილ მესხის სტადიონი',       'SPORTS',   ST_SetSRID(ST_MakePoint(44.746274, 41.709790), 4326),  150, 'way/25753200'),
    ('Tbilisi Sports Palace',     'თბილისის სპორტის სასახლე',     'SPORTS',   ST_SetSRID(ST_MakePoint(44.779597, 41.719585), 4326),  120, 'way/34148385'),
    ('Tbilisi Arena',             'თბილისი არენა',                'SPORTS',   ST_SetSRID(ST_MakePoint(44.726772, 41.718043), 4326),  120, 'way/998082831'),
    -- Landmarks people meet at
    ('Freedom Square',            'თავისუფლების მოედანი',         'LANDMARK', ST_SetSRID(ST_MakePoint(44.801514, 41.693712), 4326),  120, 'way/709370328'),
    ('Narikala',                  'ნარიყალა',                     'LANDMARK', ST_SetSRID(ST_MakePoint(44.808693, 41.687734), 4326),  200, 'relation/9820020'),
    ('Bridge of Peace',           'მშვიდობის ხიდი',               'LANDMARK', ST_SetSRID(ST_MakePoint(44.808261, 41.692983), 4326),   80, 'way/125589004'),
    ('Dry Bridge',                'მშრალი ხიდი',                  'LANDMARK', ST_SetSRID(ST_MakePoint(44.802561, 41.700473), 4326),  100, 'way/1150481713'),
    ('Fabrika',                   'ფაბრიკა',                      'LANDMARK', ST_SetSRID(ST_MakePoint(44.802714, 41.709521), 4326),   80, 'way/548795814'),
    ('Holy Trinity Cathedral',    'წმინდა სამების საკათედრო ტაძარი', 'LANDMARK', ST_SetSRID(ST_MakePoint(44.816522, 41.697550), 4326), 150, 'way/585352693');

-- ---------------------------------------------------------------------------------------
-- A plan's place
-- ---------------------------------------------------------------------------------------
-- Derived from the plan's coordinates, never sent by the client. Every way a plan gets a
-- point - the create form, a searched place, a dropped pin, an edit - then links the same
-- way, and a plan made a year before its lake was added links the moment a later migration
-- re-runs the backfill below. ON DELETE SET NULL: removing a place un-links its plans, it
-- doesn't delete where they are.

ALTER TABLE locations
    ADD COLUMN place_id uuid REFERENCES places (place_id) ON DELETE SET NULL;

CREATE INDEX idx_locations_place_id ON locations (place_id) WHERE place_id IS NOT NULL;

-- Of the places whose radius covers the point, the smallest - the most specific - then the
-- nearest. Places nest: Mikheil Meskhi Stadium (150m) sits inside Vake Park's 450m, and a
-- plan 95m from the stadium's centre is at the stadium even when the park's centre is
-- closer. Geography for the radius test, since radius_m is metres and the column is degrees.
CREATE OR REPLACE FUNCTION app_place_for_point(p_point geometry)
    RETURNS uuid
    LANGUAGE sql
    STABLE
    SET search_path = pg_catalog, public
AS $$
    SELECT p.place_id
    FROM places p
    WHERE p_point IS NOT NULL
      AND ST_DWithin(p.geom_point::geography, p_point::geography, p.radius_m)
    ORDER BY p.radius_m, ST_Distance(p.geom_point::geography, p_point::geography)
    LIMIT 1
$$;

REVOKE ALL ON FUNCTION app_place_for_point(geometry) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_place_for_point(geometry) TO linkup_app;

-- A trigger rather than a call in the Java write path: locations are written by the create
-- handler, the text-parse path and the edit path, and a trigger is the one place none of
-- them can forget. It runs as the invoker (linkup_app on a request), which needs only
-- SELECT on places. Hibernate's UPDATE sets every mapped column, geom_point included, so
-- `UPDATE OF geom_point` fires on every entity save.
CREATE OR REPLACE FUNCTION app_locations_link_place()
    RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, public
AS $$
BEGIN
    NEW.place_id := app_place_for_point(NEW.geom_point);
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION app_locations_link_place() FROM PUBLIC;

CREATE TRIGGER locations_link_place
    BEFORE INSERT OR UPDATE OF geom_point ON locations
    FOR EACH ROW
    EXECUTE FUNCTION app_locations_link_place();

-- Plans that already exist. Runs as the owner, so RLS on locations doesn't hide any rows.
UPDATE locations
SET place_id = app_place_for_point(geom_point)
WHERE geom_point IS NOT NULL;
