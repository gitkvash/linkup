package ge.kcamp.linkup.activity.query;

import ge.kcamp.linkup.activity.ActivityStatusResolver;
import ge.kcamp.linkup.activity.ActivityVisibilitySql;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Which plans a person took part in - the candidate ids behind a profile's plan lists
 * and the stats screen. Ids only: the rows themselves are read through
 * {@link ActivityQueryRepository#findByIds}, so there is still one projection and one
 * place that resolves a plan's status.
 * <p>
 * "Took part" is hosting it or having JOINED it; an invitation nobody answered is not
 * taking part. The creator always holds a JOINED row of their own (see
 * {@code ActivityPersistenceService}), so the participants table alone answers it.
 * <p>
 * Every query composes {@link ActivityVisibilitySql#VISIBLE_TO_VIEWER}: what one person
 * did is only listed to another as far as the other may see it.
 */
@Repository
public class ActivityHistoryRepository {

    /** Bound on one person's plans read at once, so a pathological account can't ask for all. */
    private static final int MAX_CANDIDATES = 1000;

    private static final String TOOK_PART = """
            EXISTS (
                SELECT 1 FROM participants tp
                WHERE tp.activity_id = a.activity_id AND tp.user_id = %s AND tp.status = 'JOINED'
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ActivityHistoryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Plans {@code personId} is in that could still be ahead of them: not ended by the
     * host, and starting no earlier than yesterday - or repeating, whose first start can
     * be long past. A superset of "not over"; the caller keeps what the resolved status
     * says is upcoming or live.
     */
    public List<UUID> findPossiblyUpcoming(UUID personId, UUID viewerId) {
        String sql = "SELECT a.activity_id FROM activities a WHERE "
                + TOOK_PART.formatted(":personId")
                + " AND a.ended_at IS NULL"
                + " AND (a.start_time >= now() - interval '1 day' OR a.repeat_freq IS NOT NULL)"
                + " AND " + ActivityVisibilitySql.VISIBLE_TO_VIEWER
                + " ORDER BY a.start_time LIMIT " + MAX_CANDIDATES;

        Map<String, Object> params = new HashMap<>();
        params.put("personId", personId);
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM, requireViewer(viewerId));
        return jdbcTemplate.queryForList(sql, params, UUID.class);
    }

    /**
     * Plans both took part in that have already started, newest first. A superset of
     * "happened together"; the caller keeps the ones whose status is ended.
     */
    public List<UUID> findStartedTogether(UUID viewerId, UUID otherId) {
        return jdbcTemplate.queryForList(startedTogether("a.activity_id"),
                startedTogetherParams(viewerId, otherId), UUID.class);
    }

    /** {@link #findStartedTogether}, as only what counting the ones that happened needs. */
    public List<Resolvable> findStartedTogetherResolvable(UUID viewerId, UUID otherId) {
        return jdbcTemplate.query(startedTogether(RESOLVABLE_COLUMNS),
                startedTogetherParams(viewerId, otherId), ActivityHistoryRepository::mapResolvable);
    }

    private static String startedTogether(String columns) {
        return "SELECT " + columns + " FROM activities a WHERE "
                + TOOK_PART.formatted(":viewerId")
                + " AND " + TOOK_PART.formatted(":otherId")
                + " AND a.start_time < now()"
                + " AND " + ActivityVisibilitySql.VISIBLE_TO_VIEWER
                + " ORDER BY a.start_time DESC LIMIT " + MAX_CANDIDATES;
    }

    private static Map<String, Object> startedTogetherParams(UUID viewerId, UUID otherId) {
        Map<String, Object> params = new HashMap<>();
        params.put("otherId", otherId);
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM, requireViewer(viewerId));
        return params;
    }

    /**
     * Plans {@code personId} took part in that start in {@code [from, to)} and that
     * {@code viewerId} may see, oldest first. The two are the same person on the stats
     * screen.
     */
    public List<UUID> findTookPartBetween(UUID personId, UUID viewerId, Instant from, Instant to) {
        return jdbcTemplate.queryForList(tookPartBetween("a.activity_id"),
                tookPartBetweenParams(personId, viewerId, from, to), UUID.class);
    }

    /** {@link #findTookPartBetween}, as only what counting the ones that happened needs. */
    public List<Resolvable> findTookPartBetweenResolvable(UUID personId, UUID viewerId, Instant from, Instant to) {
        return jdbcTemplate.query(tookPartBetween(RESOLVABLE_COLUMNS),
                tookPartBetweenParams(personId, viewerId, from, to), ActivityHistoryRepository::mapResolvable);
    }

    private static String tookPartBetween(String columns) {
        return "SELECT " + columns + " FROM activities a WHERE "
                + TOOK_PART.formatted(":personId")
                + " AND a.start_time >= :from AND a.start_time < :to"
                + " AND " + ActivityVisibilitySql.VISIBLE_TO_VIEWER
                + " ORDER BY a.start_time LIMIT " + MAX_CANDIDATES;
    }

    private static Map<String, Object> tookPartBetweenParams(
            UUID personId, UUID viewerId, Instant from, Instant to) {
        Map<String, Object> params = new HashMap<>();
        params.put("from", Timestamp.from(from));
        params.put("to", Timestamp.from(to));
        params.put("personId", personId);
        params.put(ActivityVisibilitySql.VIEWER_ID_PARAM, requireViewer(viewerId));
        return params;
    }

    /**
     * A candidate as only its category and the columns its status is resolved from. For
     * a count, where the feed projection - creator, place, group, participant count, the
     * viewer's own status, each a join or a subquery per row - would be read and dropped.
     * The status is still resolved by {@link ActivityStatusResolver}, not restated in SQL:
     * whether a repeating plan has ended needs its occurrences walked.
     */
    public record Resolvable(ActivityCategory category, ActivityStatusResolver.Lifecycle lifecycle) {
    }

    private static final String RESOLVABLE_COLUMNS = """
            a.category, a.start_time, a.end_time, a.has_time, a.repeat_freq, a.repeat_interval,
            a.repeat_until, a.started_at, a.ended_at, a.cancelled_at""";

    private static Resolvable mapResolvable(ResultSet rs, int rowNum) throws SQLException {
        String repeatFreq = rs.getString("repeat_freq");
        return new Resolvable(
                ActivityCategory.valueOf(rs.getString("category")),
                new ActivityStatusResolver.Lifecycle(
                        zoned(rs, "start_time"),
                        zoned(rs, "end_time"),
                        rs.getBoolean("has_time"),
                        repeatFreq == null ? null : RepeatFrequency.valueOf(repeatFreq),
                        // getInt() answers 0 for SQL NULL, and "every 0 weeks" is not a rule.
                        rs.getObject("repeat_interval") == null ? null : rs.getInt("repeat_interval"),
                        zoned(rs, "repeat_until"),
                        zoned(rs, "started_at"),
                        zoned(rs, "ended_at"),
                        zoned(rs, "cancelled_at")));
    }

    private static ZonedDateTime zoned(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toZonedDateTime();
    }

    /**
     * Who else was in each plan: everyone JOINED, host included, bar {@code userId}.
     * Readable as the request role because {@code participants_select_policy} shows the
     * rows of any plan the caller may see, and these are all plans they were in.
     */
    public Map<UUID, List<UUID>> findCompanions(Collection<UUID> activityIds, UUID userId) {
        Map<UUID, List<UUID>> companions = new LinkedHashMap<>();
        if (activityIds.isEmpty()) {
            return companions;
        }
        String sql = """
                SELECT p.activity_id, p.user_id FROM participants p
                WHERE p.activity_id IN (:activityIds) AND p.status = 'JOINED' AND p.user_id <> :userId
                """;
        Map<String, Object> params = new HashMap<>();
        params.put("activityIds", activityIds);
        params.put("userId", requireViewer(userId));
        jdbcTemplate.query(sql, params, rs -> {
            companions.computeIfAbsent((UUID) rs.getObject("activity_id"), id -> new ArrayList<>())
                    .add((UUID) rs.getObject("user_id"));
        });
        return companions;
    }

    private static UUID requireViewer(UUID viewerId) {
        return Objects.requireNonNull(viewerId, "viewerId is required to read activities");
    }
}
