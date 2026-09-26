package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.activity.ActivityStatusSql;
import ge.kcamp.linkup.activity.ActivityVisibilitySql;
import ge.kcamp.linkup.activity.dto.BoundingBox;
import ge.kcamp.linkup.activity.dto.MapClusterMemberDto;
import ge.kcamp.linkup.activity.dto.MapPlaceDto;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.PlaceKind;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Read side for the well-known places (V31, kept in step with OSM since V32): the ones in a
 * viewport, each with how many plans are on there this week, and the plans behind that
 * number. A hidden place is neither drawn nor listed.
 * <p>
 * Both queries count and list through the same predicate - linked to the place, visible to
 * the caller, not over, starting within {@link #WINDOW} - so the number on the map is always
 * the length of the list a tap opens.
 */
@Repository
public class PlaceRepository {

    /**
     * What "this week" means: starts within the next seven days. A plan already under way is
     * included (its start is in the past), as is a repeating plan whose rule hasn't run out -
     * {@link ActivityStatusSql#NOT_ENDED} keeps those, and a weekly one does come round within
     * the window.
     */
    private static final String WINDOW = "a.start_time < now() + interval '7 days'";

    /**
     * Ceiling on places per viewport. The OSM sync puts about 150 in Tbilisi alone, so a
     * city-wide viewport can reach it. The query keeps the largest, which are the ones
     * worth drawing at that scale.
     */
    private static final int MAX_PLACES = 200;

    /** Ceiling on the plans a place lists, the same as a cluster's. */
    public static final int MAX_PLACE_PLANS = ActivityMapRepository.MAX_CLUSTER_MEMBERS;

    private static final String PLAN_PREDICATE = """
            l.place_id = p.place_id
              AND %s
              AND %s
              AND %s
            """.formatted(WINDOW, ActivityStatusSql.NOT_ENDED, ActivityVisibilitySql.VISIBLE_TO_VIEWER);

    /**
     * A place smaller than {@code :minRadius} is left out unless it has plans this week,
     * since a place with plans is worth drawing at any zoom. See {@link #minRadiusForZoom}.
     */
    private static final String IN_BOUNDS_QUERY = """
            SELECT * FROM (
                SELECT p.place_id, p.name, p.name_ka, p.kind, p.radius_m,
                       ST_Y(p.geom_point) AS lat,
                       ST_X(p.geom_point) AS lng,
                       (SELECT count(*)
                        FROM locations l
                        JOIN activities a ON a.activity_id = l.activity_id
                        WHERE %s) AS plans_this_week
                FROM places p
                WHERE p.geom_point && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                  AND NOT p.hidden
            ) in_view
            WHERE radius_m >= :minRadius OR plans_this_week > 0
            ORDER BY plans_this_week > 0 DESC, radius_m DESC, name
            LIMIT %d
            """.formatted(PLAN_PREDICATE, MAX_PLACES);

    private static final String PLANS_QUERY = """
            SELECT a.activity_id, a.title, a.category, a.start_time, a.has_time,
                   l.address_text,
                   (a.started_at IS NOT NULL
                    OR (a.has_time AND now() >= a.start_time + interval '5 minutes')) AS is_live,
                   ST_Y(l.geom_point) AS lat,
                   ST_X(l.geom_point) AS lng
            FROM places p
            JOIN locations l ON l.place_id = p.place_id
            JOIN activities a ON a.activity_id = l.activity_id
            WHERE p.place_id = :placeId
              AND NOT p.hidden
              AND l.geom_point IS NOT NULL
              AND %s
            ORDER BY a.start_time, a.activity_id
            LIMIT %d
            """.formatted(PLAN_PREDICATE, MAX_PLACE_PLANS);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PlaceRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param zoom the map's zoom, or null for every place in the box whatever its size
     */
    public List<MapPlaceDto> findInBounds(BoundingBox bbox, UUID viewerId, Integer zoom) {
        Map<String, Object> params = new HashMap<>();
        params.put("minRadius", minRadiusForZoom(zoom));
        params.put("minLat", bbox.minLat());
        params.put("minLng", bbox.minLng());
        params.put("maxLat", bbox.maxLat());
        params.put("maxLng", bbox.maxLng());
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM,
                Objects.requireNonNull(viewerId, "viewerId is required to read places"));

        return jdbcTemplate.query(IN_BOUNDS_QUERY, params, PlaceRepository::mapPlace);
    }

    /**
     * The plans behind a place's count, soonest first, in the shape a cluster's members come
     * in - the app lists them in the same sheet. Empty for an unknown place.
     */
    public List<MapClusterMemberDto> findPlansThisWeek(UUID placeId, UUID viewerId) {
        Map<String, Object> params = new HashMap<>();
        params.put("placeId", placeId);
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM,
                Objects.requireNonNull(viewerId, "viewerId is required to read places"));

        return jdbcTemplate.query(PLANS_QUERY, params, PlaceRepository::mapPlan);
    }

    /**
     * The smallest place worth drawing at a zoom, by radius. The app draws places from zoom
     * 11, where a 24dp tile covers about 2 km. At that scale only lakes and the big parks
     * can be told apart; a mall or an arena would be one tile in a pile. Each step in
     * shrinks the bar, and from 14 everything is drawn, by which point every place is
     * labelled too.
     */
    static int minRadiusForZoom(Integer zoom) {
        if (zoom == null || zoom >= 14) {
            return 0;
        }
        if (zoom <= 11) {
            return 300;
        }
        return zoom == 12 ? 150 : 80;
    }

    private static MapPlaceDto mapPlace(ResultSet rs, int rowNum) throws SQLException {
        return new MapPlaceDto(
                (UUID) rs.getObject("place_id"),
                rs.getString("name"),
                rs.getString("name_ka"),
                PlaceKind.valueOf(rs.getString("kind")),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getInt("plans_this_week"));
    }

    // Anything over has already been filtered out by NOT_ENDED, so the only question left
    // is whether it has begun - the same rule the map's cluster members use.
    private static MapClusterMemberDto mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new MapClusterMemberDto(
                (UUID) rs.getObject("activity_id"),
                rs.getString("title"),
                ActivityCategory.valueOf(rs.getString("category")),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getObject("start_time", OffsetDateTime.class).toZonedDateTime(),
                rs.getBoolean("has_time"),
                rs.getString("address_text"),
                rs.getBoolean("is_live") ? ActivityStatus.LIVE : ActivityStatus.UPCOMING);
    }
}
