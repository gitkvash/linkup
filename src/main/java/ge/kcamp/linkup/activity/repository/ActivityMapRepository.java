package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.activity.ActivityVisibilitySql;
import ge.kcamp.linkup.activity.dto.BoundingBox;
import ge.kcamp.linkup.activity.dto.MapMarkerDto;
import ge.kcamp.linkup.activity.enums.ActivityType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
                SELECT a.activity_id, a.title, a.activity_type, l.geom_point
                FROM activities a
                JOIN locations l ON l.activity_id = a.activity_id
                WHERE l.geom_point IS NOT NULL
                  AND l.geom_point && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                  AND a.start_time >= :notBefore
                  AND %s
                LIMIT %d
            ), clustered AS (
                SELECT activity_id, title, activity_type, geom_point,
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
                   (array_agg(activity_type))[1] AS any_activity_type
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
                    ActivityType.valueOf(rs.getString("any_activity_type")));
        }
        return new MapMarkerDto(MapMarkerDto.MarkerType.CLUSTER, lat, lng, count, null, null, null);
    }
}
