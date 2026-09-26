package ge.kcamp.linkup.activity;

/**
 * {@link ActivityStatusResolver}'s rule, in SQL, for the queries that have to filter on it
 * before any Java sees a row - the map, which must not hand back pins for plans that are
 * over.
 * <p>
 * The two are deliberately the same statement said twice, for the same reason
 * {@link ActivityVisibilitySql} is: a predicate cannot be applied after clustering has
 * already used the row. Keep them in step. The one simplification here is repetition -
 * SQL walks no occurrences, so a repeating plan whose rule has not run out is simply not
 * over, which is the only thing a filter needs to know.
 */
public final class ActivityStatusSql {

    private ActivityStatusSql() {
    }

    /** How long a started plan runs: its own end time, or two hours. */
    private static final String DURATION = """
            (CASE WHEN a.end_time > a.start_time
                  THEN a.end_time - a.start_time
                  ELSE interval '2 hours' END)""";

    /**
     * Neither ended nor cancelled. Requires the {@code activities} table to be aliased
     * {@code a}. Wrapped in parentheses so it can be AND-ed into any WHERE clause safely.
     * <p>
     * A plan nobody started is over two hours after its start time (cancelled); a
     * date-only one a day after its start, which is stored at the creator's local
     * midnight - the same rule the resolver uses. Not {@code date_trunc('day', ...)}:
     * that truncates in the server's zone (UTC), which ended a Tbilisi plan at 04:00 on
     * its own day.
     */
    public static final String NOT_OVER = """
            (
                a.cancelled_at IS NULL
                AND (
                    (a.repeat_freq IS NOT NULL
                        AND (a.repeat_until IS NULL OR a.repeat_until >= now()))
                    OR (a.started_at IS NOT NULL AND a.ended_at IS NULL
                        AND now() < a.started_at + %1$s)
                    OR (a.started_at IS NULL AND a.ended_at IS NULL
                        AND now() < (CASE WHEN a.has_time THEN a.start_time + interval '2 hours'
                                          ELSE a.start_time + interval '1 day' END))
                )
            )
            """.formatted(DURATION);

    /**
     * Happening now: the host started it and its window hasn't run out. A plan never
     * goes live by the clock. Same alias and parentheses as {@link #NOT_OVER}.
     */
    public static final String IS_LIVE = """
            (a.cancelled_at IS NULL AND a.started_at IS NOT NULL AND a.ended_at IS NULL
             AND now() < a.started_at + %1$s)""".formatted(DURATION);
}
