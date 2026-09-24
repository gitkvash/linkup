package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.activity.ActivityStatusSql;
import ge.kcamp.linkup.activity.ActivityVisibilitySql;
import ge.kcamp.linkup.activity.dto.BoundingBox;
import ge.kcamp.linkup.activity.dto.MapClusterMemberDto;
import ge.kcamp.linkup.activity.dto.MapMarkerDto;
import ge.kcamp.linkup.activity.dto.MapSearchResultDto;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ActivityType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    /**
     * Ceiling on the members a cluster carries. The list opened from a cluster is for the
     * handful of plans zooming can't separate - several at one address - not for browsing
     * a city-sized cluster, which the client zooms into instead.
     */
    public static final int MAX_CLUSTER_MEMBERS = 25;

    // ST_ClusterDBSCAN returns NULL for noise points; grouping naively by cluster_id
    // would collapse every noise point in the bbox into one giant "cluster" - the
    // COALESCE below gives each noise point its own unique group key instead.
    //
    // Rows come back one per activity, not one per group: a cluster now carries its
    // members, and grouping in Java keeps each member's row intact where a GROUP BY would
    // need an array_agg per field. The centroid is the mean of the members' coordinates,
    // which is what ST_Centroid of a MULTIPOINT is.
    private static final String CLUSTER_QUERY = """
            WITH visible AS (
                SELECT a.activity_id, a.title, a.activity_type, a.category,
                       a.start_time, a.has_time, l.address_text,
                       (a.started_at IS NOT NULL
                        OR (a.has_time AND now() >= a.start_time + interval '5 minutes')) AS is_live,
                       l.geom_point
                FROM activities a
                JOIN locations l ON l.activity_id = a.activity_id
                WHERE l.geom_point IS NOT NULL
                  AND l.geom_point && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                  AND %s
                  AND %s
                LIMIT %d
            ), clustered AS (
                SELECT activity_id, title, activity_type, category, start_time, has_time,
                       address_text, is_live, geom_point,
                       ST_ClusterDBSCAN(ST_Transform(geom_point, 3857), eps := :epsMeters, minpoints := :minPoints)
                           OVER () AS cluster_id
                FROM visible
            )
            SELECT COALESCE(cluster_id::text, 'n_' || activity_id::text) AS group_key,
                   activity_id, title, activity_type, category, start_time, has_time,
                   address_text, is_live,
                   ST_Y(geom_point) AS lat,
                   ST_X(geom_point) AS lng
            FROM clustered
            ORDER BY start_time, activity_id
            """.formatted(ActivityStatusSql.NOT_ENDED, ActivityVisibilitySql.VISIBLE_TO_VIEWER, MAX_CLUSTERED_ROWS);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ActivityMapRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MapMarkerDto> findClusteredMarkers(
            BoundingBox bbox, double epsMeters, int minPoints, UUID viewerId) {

        Map<String, Object> params = new HashMap<>();
        params.put("minLat", bbox.minLat());
        params.put("minLng", bbox.minLng());
        params.put("maxLat", bbox.maxLat());
        params.put("maxLng", bbox.maxLng());
        params.put("epsMeters", epsMeters);
        params.put("minPoints", minPoints);
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM,
                Objects.requireNonNull(viewerId, "viewerId is required to read the map"));

        // Insertion-ordered, and the rows arrive soonest first, so each group's members
        // are already in the order the list shows them.
        Map<String, List<ClusteredRow>> groups = new LinkedHashMap<>();
        jdbcTemplate.query(CLUSTER_QUERY, params, (ResultSet rs) -> {
            groups.computeIfAbsent(rs.getString("group_key"), key -> new ArrayList<>())
                    .add(ClusteredRow.from(rs));
        });

        return groups.values().stream().map(ActivityMapRepository::toMarker).toList();
    }

    /** One activity as the cluster query returns it, before it is grouped. */
    private record ClusteredRow(
            UUID activityId,
            String title,
            ActivityType activityType,
            ActivityCategory category,
            ZonedDateTime startTime,
            boolean hasTime,
            String addressText,
            boolean live,
            double lat,
            double lng
    ) {
        static ClusteredRow from(ResultSet rs) throws SQLException {
            return new ClusteredRow(
                    (UUID) rs.getObject("activity_id"),
                    rs.getString("title"),
                    ActivityType.valueOf(rs.getString("activity_type")),
                    ActivityCategory.valueOf(rs.getString("category")),
                    rs.getObject("start_time", OffsetDateTime.class).toZonedDateTime(),
                    rs.getBoolean("has_time"),
                    rs.getString("address_text"),
                    rs.getBoolean("is_live"),
                    rs.getDouble("lat"),
                    rs.getDouble("lng"));
        }

        // Anything over has already been filtered out by ActivityStatusSql.NOT_ENDED, so
        // the only question left is whether it has begun.
        ActivityStatus status() {
            return live ? ActivityStatus.LIVE : ActivityStatus.UPCOMING;
        }

        MapClusterMemberDto toMember() {
            return new MapClusterMemberDto(
                    activityId, title, category, lat, lng, startTime, hasTime, addressText, status());
        }
    }

    private static MapMarkerDto toMarker(List<ClusteredRow> rows) {
        if (rows.size() == 1) {
            ClusteredRow row = rows.getFirst();
            return new MapMarkerDto(
                    MapMarkerDto.MarkerType.PIN, row.lat(), row.lng(), 1,
                    row.activityId(), row.title(), row.activityType(), row.category(),
                    row.status(), null);
        }

        double lat = rows.stream().mapToDouble(ClusteredRow::lat).average().orElseThrow();
        double lng = rows.stream().mapToDouble(ClusteredRow::lng).average().orElseThrow();

        // A cluster is several plans of possibly several kinds: it has no one category to
        // draw, and picking the first row's would label the whole group with it. Its
        // status is the loudest of them - one live plan makes the cluster worth looking at.
        ActivityStatus status = rows.stream().anyMatch(ClusteredRow::live)
                ? ActivityStatus.LIVE
                : ActivityStatus.UPCOMING;

        List<MapClusterMemberDto> members = rows.stream()
                .limit(MAX_CLUSTER_MEMBERS)
                .map(ClusteredRow::toMember)
                .toList();

        return new MapMarkerDto(
                MapMarkerDto.MarkerType.CLUSTER, lat, lng, rows.size(),
                null, null, null, null, status, members);
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
              AND (a.title ILIKE :pattern OR l.address_text ILIKE :pattern)
              AND %s
              AND %s
            ORDER BY distance_meters, a.start_time
            LIMIT :limit
            """.formatted(ActivityStatusSql.NOT_ENDED, ActivityVisibilitySql.VISIBLE_TO_VIEWER);

    public List<MapSearchResultDto> searchNearby(
            String query, double focusLat, double focusLng, int limit, UUID viewerId) {

        Map<String, Object> params = new HashMap<>();
        params.put("pattern", "%" + escapeLikeWildcards(query.strip()) + "%");
        params.put("focusLat", focusLat);
        params.put("focusLng", focusLng);
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
