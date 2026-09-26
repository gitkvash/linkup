package ge.kcamp.linkup.activity.enums;

/**
 * Where a plan is in its own life.
 * <p>
 * Never stored: see {@code V25__activity_lifecycle.sql} for why, and
 * {@link ge.kcamp.linkup.activity.ActivityStatusResolver} for the rule that produces it.
 */
public enum ActivityStatus {

    /** Hasn't begun. The default, and the only state a plan can be created in. */
    UPCOMING,

    /** Happening now: the host started it. A plan never starts by the clock alone. */
    LIVE,

    /** Over: the host ended it, or it ran past the end of its window after being started. */
    ENDED,

    /**
     * Didn't happen: the host cancelled it, or nobody started it within
     * {@link ge.kcamp.linkup.activity.ActivityStatusResolver#START_GRACE} of its start time.
     * Deleting a plan is still separate - that removes it for everyone.
     */
    CANCELLED;

    /** Ended or cancelled: nothing left to join, start or remind anyone about. */
    public boolean isOver() {
        return this == ENDED || this == CANCELLED;
    }
}
