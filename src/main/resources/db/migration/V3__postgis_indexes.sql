CREATE INDEX idx_locations_geom_point ON Locations USING GIST (geom_point);
