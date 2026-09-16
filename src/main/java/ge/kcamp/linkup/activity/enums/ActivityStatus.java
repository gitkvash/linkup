package ge.kcamp.linkup.activity.enums;

/**
 * Where a plan is in its own life.
 * <p>
 * Never stored: see {@code V25__activity_lifecycle.sql} for why, and
 * {@link ge.kcamp.linkup.activity.ActivityStatusResolver} for the rule that produces it.
 * There is no CANCELLED - cancelling a plan deletes it, which is what
 * {@code DELETE /activities/{id}} has always done.
 */
public enum ActivityStatus {

    /** Hasn't begun. The default, and the only state a plan can be created in. */
    UPCOMING,

    /** Happening now: the host started it, or its start time has passed. */
    LIVE,

    /** Over: the host ended it, or it ran past the end of its window. */
    ENDED
}
