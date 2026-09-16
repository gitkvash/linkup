package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.activity.ActivityVisibilitySql;
import ge.kcamp.linkup.activity.dto.BoundingBox;
import ge.kcamp.linkup.activity.dto.MapMarkerDto;
import ge.kcamp.linkup.activity.dto.MapSearchResultDto;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Query side for the discovery map: groups nearby activities into clusters using
 * PostGIS ST_ClusterDBSCAN so the client never has to render more markers than the
 * viewport can handle.
 * <p>
 * The visibility predicate sits <em>inside</em> the CTE. It has to: clustering runs
 * over whatever rows the CTE yields, so filtering afterwards would still have let a
 * private activity influence a cluster's position and count. Previously there was no
 * visibility filter at all here, which meant every private and friends-only plan in
 * the viewport was returned - and a lone one came back as a PIN carrying its title and
 * exact coordinates.
 */
@Repository
public class ActivityMapRepository {

    /**
     * Ceiling on rows fed into the clustering window. DBSCAN over an unbounded set is
     * the one query here a single request could use to hurt the database.
     */
    private static final int MAX_CLUSTERED_ROWS = 2000;

    // ST_ClusterDBSCAN returns NULL for noise points; grouping naively by cluster_id
    // would collapse every noise point in the bbox into one giant "cluster" - the
    // COALESCE below gives each noise point its own unique group key instead.
    private static final String CLUSTER_QUERY = """
            WITH visible AS (
                SELECT a.activity_id, a.title, a.activity_type, a.category, l.geom_point
                FROM activities a
                JOIN locations l ON l.activity_id = a.activity_id
                WHERE l.geom_point IS NOT NULL
                  AND l.geom_point && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                  AND a.start_time >= :notBefore
                  AND %s
                LIMIT %d
            ), clustered AS (
                SELECT activity_id, title, activity_type, category, geom_point,
                       ST_ClusterDBSCAN(ST_Transform(geom_point, 3857), eps := :epsMeters, minpoints := :minPoints)
                           OVER () AS cluster_id
                FROM visible
            )
            SELECT COALESCE(cluster_id::text, 'n_' || activity_id::text) AS group_key,
                   count(*) AS cnt,
                   ST_Y(ST_Centroid(ST_Collect(geom_point))) AS lat,
                   ST_X(ST_Centroid(ST_Collect(geom_point))) AS lng,
                   (array_agg(activity_id))[1] AS any_activity_id,
                   (array_agg(title))[1] AS any_title,
                   (array_agg(activity_type))[1] AS any_activity_type,
                   (array_agg(category))[1] AS any_category
            FROM clustered
            GROUP BY 1
            """.formatted(ActivityVisibilitySql.VISIBLE_TO_VIEWER, MAX_CLUSTERED_ROWS);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ActivityMapRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MapMarkerDto> findClusteredMarkers(
            BoundingBox bbox, double epsMeters, int minPoints, Instant notBefore, UUID viewerId) {

        Map<String, Object> params = new HashMap<>();
        params.put("minLat", bbox.minLat());
        params.put("minLng", bbox.minLng());
        params.put("maxLat", bbox.maxLat());
        params.put("maxLng", bbox.maxLng());
        params.put("epsMeters", epsMeters);
        params.put("minPoints", minPoints);
        params.put("notBefore", Timestamp.from(notBefore));
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM,
                Objects.requireNonNull(viewerId, "viewerId is required to read the map"));

        return jdbcTemplate.query(CLUSTER_QUERY, params, ActivityMapRepository::mapRow);
    }

    private static MapMarkerDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        int count = rs.getInt("cnt");
        double lat = rs.getDouble("lat");
        double lng = rs.getDouble("lng");

        if (count == 1) {
            return new MapMarkerDto(
                    MapMarkerDto.MarkerType.PIN, lat, lng, count,
                    (UUID) rs.getObject("any_activity_id"),
                    rs.getString("any_title"),
                    ActivityType.valueOf(rs.getString("any_activity_type")),
                    ActivityCategory.valueOf(rs.getString("any_category")));
        }
        // A cluster is several plans of possibly several kinds: it has no one category to
        // draw, and picking the first row's would label the whole group with it.
        return new MapMarkerDto(MapMarkerDto.MarkerType.CLUSTER, lat, lng, count, null, null, null, null);
    }

    /**
     * Ceiling on rows a single search may return. The list is a panel over the map, not
     * a results page — past a handful the user pans instead of scrolls.
     */
    public static final int MAX_SEARCH_RESULTS = 25;

    /**
     * Free-text search over the same rows the map draws.
     * <p>
     * Not clustered and not bounded by the viewport: searching is how a user finds the
     * plan they can't see, so a match just off screen has to come back with somewhere to
     * fly to. The bound is the result limit and the distance ordering instead - nearest
     * to where the user is looking first, then soonest.
     * <p>
     * {@code ST_DistanceSphere} rather than a {@code geography} cast: the column is a
     * 4326 {@code geometry} (V11) and the spheroid's extra accuracy is meaningless for an
     * ordering the user reads as "near me".
     */
    private static final String SEARCH_QUERY = """
            SELECT a.activity_id, a.title, a.category, a.start_time, a.has_time,
                   l.address_text,
                   ST_Y(l.geom_point) AS lat,
                   ST_X(l.geom_point) AS lng,
                   ST_DistanceSphere(l.geom_point, ST_SetSRID(ST_MakePoint(:focusLng, :focusLat), 4326)) AS distance_meters
            FROM activities a
            JOIN locations l ON l.activity_id = a.activity_id
            WHERE l.geom_point IS NOT NULL
              AND a.start_time >= :notBefore
              AND (a.title ILIKE :pattern OR l.address_text ILIKE :pattern)
              AND %s
            ORDER BY distance_meters, a.start_time
            LIMIT :limit
            """.formatted(ActivityVisibilitySql.VISIBLE_TO_VIEWER);

    public List<MapSearchResultDto> searchNearby(
            String query, double focusLat, double focusLng, int limit, Instant notBefore, UUID viewerId) {

        Map<String, Object> params = new HashMap<>();
        params.put("pattern", "%" + escapeLikeWildcards(query.strip()) + "%");
        params.put("focusLat", focusLat);
        params.put("focusLng", focusLng);
        params.put("notBefore", Timestamp.from(notBefore));
        params.put("limit", Math.min(Math.max(limit, 1), MAX_SEARCH_RESULTS));
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM,
                Objects.requireNonNull(viewerId, "viewerId is required to search the map"));

        return jdbcTemplate.query(SEARCH_QUERY, params, ActivityMapRepository::mapSearchRow);
    }

    /**
     * A user typing {@code %} means a literal percent sign, not "match anything" - and
     * a bare {@code %} would otherwise return every future activity they can see.
     * PostgreSQL's default LIKE escape character is the backslash, so escaping it first
     * is what keeps the other two replacements honest.
     */
    private static String escapeLikeWildcards(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static MapSearchResultDto mapSearchRow(ResultSet rs, int rowNum) throws SQLException {
        return new MapSearchResultDto(
                (UUID) rs.getObject("activity_id"),
                rs.getString("title"),
                ActivityCategory.valueOf(rs.getString("category")),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getObject("start_time", OffsetDateTime.class).toZonedDateTime(),
                rs.getBoolean("has_time"),
                rs.getString("address_text"),
                rs.getDouble("distance_meters"));
    }
}
