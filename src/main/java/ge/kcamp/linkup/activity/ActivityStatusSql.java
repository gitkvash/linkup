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

    /**
     * Requires the {@code activities} table to be aliased {@code a}. Wrapped in
     * parentheses so it can be AND-ed into any WHERE clause safely.
     * <p>
     * {@code date_trunc} on a {@code timestamptz} truncates in the server's time zone
     * rather than the one the plan was created in, so a date-only plan can end up to a
     * few hours early or late for a user who is elsewhere. That is the cost of not
     * storing the zone; the resolver, which has the offset the row was written with, does
     * not pay it.
     */
    public static final String NOT_ENDED = """
            (
                a.ended_at IS NULL
                AND (
                    (a.repeat_freq IS NOT NULL
                        AND (a.repeat_until IS NULL OR a.repeat_until >= now()))
                    OR now() < (
                        CASE
                            WHEN a.started_at IS NOT NULL THEN a.started_at + (
                                CASE WHEN a.end_time > a.start_time
                                     THEN a.end_time - a.start_time
                                     ELSE interval '2 hours' END)
                            WHEN a.has_time THEN a.start_time + (
                                CASE WHEN a.end_time > a.start_time
                                     THEN a.end_time - a.start_time
                                     ELSE interval '2 hours' END)
                            ELSE date_trunc('day', a.start_time) + interval '1 day'
                        END)
                )
            )
            """;
}
