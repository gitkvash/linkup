package ge.kcamp.linkup.activity.query;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityStatusResolver;
import ge.kcamp.linkup.activity.ActivityVisibilitySql;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Query side of activity CQRS: bypasses the JPA domain model entirely and reads a
 * denormalized projection directly.
 * <p>
 * Every method takes the viewer's id and composes
 * {@link ActivityVisibilitySql#VISIBLE_TO_VIEWER} - visibility is enforced here, in
 * Java-issued SQL, not by row-level security (see that class for why).
 */
@Repository
public class ActivityQueryRepository {

    /** Rows read at once by the map/feed batch paths, to bound a pathological request. */
    private static final int MAX_ROWS = 200;

    private static final String BASE_SELECT = """
            SELECT a.activity_id, a.creator_id, u.username AS creator_username,
                   u.display_name AS creator_display_name,
                   a.title, a.activity_type, a.visibility, a.category,
                   a.start_time, a.end_time, a.has_time, a.started_at, a.ended_at,
                   l.address_text,
                   ST_Y(l.geom_point) AS lat, ST_X(l.geom_point) AS lng,
                   (SELECT count(*) FROM participants p
                     WHERE p.activity_id = a.activity_id AND p.status = 'JOINED') AS participant_count,
                   (SELECT vs.status FROM participants vs
                     WHERE vs.activity_id = a.activity_id AND vs.user_id = :viewerId) AS viewer_status,
                   a.group_id, g.group_name,
                   a.repeat_freq, a.repeat_interval, a.repeat_until
            FROM activities a
            LEFT JOIN locations l ON l.activity_id = a.activity_id
            LEFT JOIN groups g ON g.group_id = a.group_id
            -- LEFT, not INNER: a deleted account must not take its plans out of
            -- everyone's feed. username is then null, which the DTO allows.
            LEFT JOIN users u ON u.user_id = a.creator_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ActivityQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ActivityFeedItem> findById(UUID activityId, UUID viewerId) {
        String sql = BASE_SELECT
                + " WHERE a.activity_id = :activityId AND " + ActivityVisibilitySql.VISIBLE_TO_VIEWER;

        Map<String, Object> params = new HashMap<>();
        params.put("activityId", activityId);
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM, requireViewer(viewerId));

        return jdbcTemplate.query(sql, params, ActivityQueryRepository::mapRow).stream().findFirst();
    }

    /**
     * Batch counterpart to {@link #findById}. The feed used to call findById once per
     * timeline entry, so a 20-item page issued 20 round trips, each with its own
     * correlated participant-count subquery.
     */
    public List<ActivityFeedItem> findByIds(List<UUID> activityIds, UUID viewerId) {
        if (activityIds.isEmpty()) {
            return List.of();
        }
        String sql = BASE_SELECT
                + " WHERE a.activity_id IN (:activityIds) AND " + ActivityVisibilitySql.VISIBLE_TO_VIEWER
                + " ORDER BY a.start_time DESC LIMIT " + MAX_ROWS;

        Map<String, Object> params = new HashMap<>();
        params.put("activityIds", activityIds);
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM, requireViewer(viewerId));

        return jdbcTemplate.query(sql, params, ActivityQueryRepository::mapRow);
    }

    /**
     * The caller's own activities. No visibility predicate needed - being the creator
     * satisfies it - but it still binds {@code :viewerId} because BASE_SELECT projects
     * {@code viewer_status}.
     */
    public List<ActivityFeedItem> findByCreator(UUID creatorId) {
        String sql = BASE_SELECT
                + " WHERE a.creator_id = :creatorId ORDER BY a.start_time DESC LIMIT " + MAX_ROWS;

        Map<String, Object> params = new HashMap<>();
        params.put("creatorId", creatorId);
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM, requireViewer(creatorId));

        return jdbcTemplate.query(sql, params, ActivityQueryRepository::mapRow);
    }

    public List<ActivityFeedItem> findByCreatorIn(List<UUID> creatorIds, Instant after, UUID viewerId) {
        return findByCreatorIn(creatorIds, after, null, viewerId);
    }

    /**
     * @param notAfter upper bound on start time, inclusive, or null for none. The feed
     *                 pages newest-start-first, so without it every page past the first
     *                 re-read the same newest {@value #MAX_ROWS} rows and filtered them
     *                 away, and anything older than those was never served at all.
     */
    public List<ActivityFeedItem> findByCreatorIn(
            List<UUID> creatorIds, Instant after, Instant notAfter, UUID viewerId) {
        if (creatorIds.isEmpty()) {
            return List.of();
        }
        String sql = BASE_SELECT
                + " WHERE a.creator_id IN (:creatorIds) AND a.start_time >= :after"
                + (notAfter == null ? "" : " AND a.start_time <= :notAfter")
                + " AND " + ActivityVisibilitySql.VISIBLE_TO_VIEWER
                + " ORDER BY a.start_time DESC LIMIT " + MAX_ROWS;

        Map<String, Object> params = new HashMap<>();
        params.put("creatorIds", creatorIds);
        params.put("after", Timestamp.from(after));
        if (notAfter != null) {
            params.put("notAfter", Timestamp.from(notAfter));
        }
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM, requireViewer(viewerId));

        return jdbcTemplate.query(sql, params, ActivityQueryRepository::mapRow);
    }

    /**
     * A null viewer would make every visibility branch evaluate to NULL and quietly
     * return nothing, which is exactly the silent-empty-result failure mode that hid
     * the RLS problem. Fail loudly instead.
     */
    private static UUID requireViewer(UUID viewerId) {
        return Objects.requireNonNull(viewerId, "viewerId is required to read activities");
    }

    private static ActivityFeedItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime endTime = rs.getObject("end_time", OffsetDateTime.class);
        String repeatFreq = rs.getString("repeat_freq");
        OffsetDateTime repeatUntil = rs.getObject("repeat_until", OffsetDateTime.class);
        // getInt() answers 0 for SQL NULL, and "every 0 weeks" is not a rule.
        Integer repeatInterval = rs.getObject("repeat_interval") == null ? null : rs.getInt("repeat_interval");
        Double lat = rs.getObject("lat") == null ? null : rs.getDouble("lat");
        Double lng = rs.getObject("lng") == null ? null : rs.getDouble("lng");
        String viewerStatus = rs.getString("viewer_status");
        OffsetDateTime startedAt = rs.getObject("started_at", OffsetDateTime.class);
        OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
        ZonedDateTime startTime = rs.getObject("start_time", OffsetDateTime.class).toZonedDateTime();
        ZonedDateTime endsAt = endTime == null ? null : endTime.toZonedDateTime();
        RepeatFrequency frequency = repeatFreq == null ? null : RepeatFrequency.valueOf(repeatFreq);

        return new ActivityFeedItem(
                (UUID) rs.getObject("activity_id"),
                (UUID) rs.getObject("creator_id"),
                rs.getString("creator_username"),
                rs.getString("creator_display_name"),
                rs.getString("title"),
                ActivityType.valueOf(rs.getString("activity_type")),
                ActivityVisibility.valueOf(rs.getString("visibility")),
                startTime,
                endsAt,
                rs.getBoolean("has_time"),
                rs.getString("address_text"),
                lat,
                lng,
                rs.getLong("participant_count"),
                viewerStatus == null ? null : ParticipantStatus.valueOf(viewerStatus),
                (UUID) rs.getObject("group_id"),
                rs.getString("group_name"),
                ActivityCategory.valueOf(rs.getString("category")),
                frequency,
                repeatInterval,
                repeatUntil == null ? null : repeatUntil.toZonedDateTime(),
                ActivityStatusResolver.resolve(
                        new ActivityStatusResolver.Lifecycle(
                                startTime,
                                endsAt,
                                rs.getBoolean("has_time"),
                                frequency,
                                repeatInterval,
                                repeatUntil == null ? null : repeatUntil.toZonedDateTime(),
                                startedAt == null ? null : startedAt.toZonedDateTime(),
                                endedAt == null ? null : endedAt.toZonedDateTime()),
                        ZonedDateTime.now())
        );
    }
}
