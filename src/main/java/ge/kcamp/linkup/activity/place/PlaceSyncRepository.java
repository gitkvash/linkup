package ge.kcamp.linkup.activity.place;

import ge.kcamp.linkup.activity.enums.PlaceKind;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes for the OSM place sync (V32). Every statement here needs the owner: the request
 * role can only read {@code places}, and can't see {@code place_sync} at all. So this runs
 * only on {@code applicationTaskExecutor}, whose threads are stamped {@code SYSTEM}.
 * <p>
 * A sync's rows are stamped with the transaction's {@code now()}, which is the same value
 * for every statement in it. Afterwards, "not seen by this sync" is just
 * {@code synced_at < now()}, with no list of ids to pass back.
 */
@Repository
class PlaceSyncRepository {

    /**
     * A CURATED row is left alone, and so is {@code hidden} on any row: those are the two
     * things a person set, and a sync must not undo them.
     */
    private static final String UPSERT = """
            INSERT INTO places (name, name_ka, kind, geom_point, radius_m, osm_ref, source, synced_at)
            VALUES (:name, :nameKa, :kind, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :radiusM,
                    :osmRef, 'OSM', now())
            ON CONFLICT (osm_ref) DO UPDATE
                SET name       = EXCLUDED.name,
                    name_ka    = EXCLUDED.name_ka,
                    kind       = EXCLUDED.kind,
                    geom_point = EXCLUDED.geom_point,
                    radius_m   = EXCLUDED.radius_m,
                    synced_at  = EXCLUDED.synced_at
                WHERE places.source = 'OSM'
            """;

    /**
     * Only after upserting. Recomputed for every location with a point: a new place links
     * the plans already made at it, and a pruned one's plans move to whatever else covers
     * them. Pruning had already set their {@code place_id} to NULL (ON DELETE SET NULL).
     */
    private static final String RELINK = """
            UPDATE locations l
            SET place_id = linked.place_id
            FROM (SELECT location_id, app_place_for_point(geom_point) AS place_id
                  FROM locations
                  WHERE geom_point IS NOT NULL) linked
            WHERE l.location_id = linked.location_id
              AND l.place_id IS DISTINCT FROM linked.place_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PlaceSyncRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty if no sync has ever finished. */
    Optional<OffsetDateTime> lastSuccess() {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT last_success_at FROM place_sync WHERE id = 1",
                Map.of(), OffsetDateTime.class));
    }

    /**
     * {@link #lastSuccess}, holding the row until the transaction ends. A second instance
     * syncing at the same moment waits here, then sees the first one's timestamp.
     */
    Optional<OffsetDateTime> lockAndReadLastSuccess() {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT last_success_at FROM place_sync WHERE id = 1 FOR UPDATE",
                Map.of(), OffsetDateTime.class));
    }

    /** Every CURATED place, hidden or not, since a hidden one still claims its spot. */
    List<CuratedPlace> curatedPlaces() {
        return jdbc.query("""
                        SELECT osm_ref, kind, ST_Y(geom_point) AS lat, ST_X(geom_point) AS lng, radius_m
                        FROM places
                        WHERE source = 'CURATED'
                        """,
                Map.of(),
                (rs, rowNum) -> new CuratedPlace(
                        rs.getString("osm_ref"),
                        PlaceKind.valueOf(rs.getString("kind")),
                        rs.getDouble("lat"),
                        rs.getDouble("lng"),
                        rs.getInt("radius_m")));
    }

    int countSyncedPlaces() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM places WHERE source = 'OSM'", Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    void upsert(List<OsmPlace> places) {
        SqlParameterSource[] batch = places.stream()
                .map(place -> new MapSqlParameterSource()
                        .addValue("name", place.name())
                        .addValue("nameKa", place.nameKa())
                        .addValue("kind", place.kind().name())
                        .addValue("lat", place.lat())
                        .addValue("lng", place.lng())
                        .addValue("radiusM", place.radiusM())
                        .addValue("osmRef", place.osmRef()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT, batch);
    }

    /** OSM rows this sync didn't see: gone from OSM, or no longer passing the classifier. */
    int deleteNotSeenThisSync() {
        return jdbc.update(
                "DELETE FROM places WHERE source = 'OSM' AND (synced_at IS NULL OR synced_at < now())",
                Map.of());
    }

    int relinkLocations() {
        return jdbc.update(RELINK, Map.of());
    }

    void markSuccess() {
        jdbc.update("UPDATE place_sync SET last_success_at = now() WHERE id = 1", Map.of());
    }

    /** What the sync needs to know about a curated place so it doesn't add a second copy. */
    record CuratedPlace(String osmRef, PlaceKind kind, double lat, double lng, int radiusM) {
    }
}
