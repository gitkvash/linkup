package ge.kcamp.linkup.feed;

import java.time.Instant;
import java.util.UUID;

/**
 * The feed's sort key and pagination cursor, in one number.
 * <p>
 * Two things were wrong before. The Redis score was the activity's <em>creation</em>
 * instant while the cursor returned to the client was the last item's <em>start</em>
 * time; since plans are normally in the future, that cursor sat above every score, so
 * page 2 returned page 1 - forever. And a score of start-time-millis alone is not a
 * total order: a time picker yields minute-granular times, so activities routinely share
 * an exact millisecond, and any cursor scheme over a non-unique key either repeats or
 * skips rows at every page boundary.
 * <p>
 * So the score is start-time millis shifted left and salted with a few bits derived from
 * the activity id: ordering is still by start time, but ties are broken deterministically
 * and the key is effectively unique, which makes an exclusive cursor exact.
 * <p>
 * The maximum value stays inside the 2^53 integers a double represents exactly, which
 * matters because Redis sorted-set scores <em>are</em> doubles:
 * {@code 2^43 ms} (year ~2248) {@code * 1024} is about 9.0e15.
 */
final class FeedTimelineScore {

    private static final int TIE_BITS = 10;
    private static final int TIE_MASK = (1 << TIE_BITS) - 1;

    private FeedTimelineScore() {
    }

    static double of(UUID activityId, Instant startTime) {
        return of(activityId, startTime.toEpochMilli());
    }

    static double of(UUID activityId, long startMillis) {
        long tieBreaker = Math.abs(activityId.getMostSignificantBits() ^ activityId.getLeastSignificantBits()) & TIE_MASK;
        return (double) ((startMillis << TIE_BITS) | tieBreaker);
    }

    /**
     * The start-time floor implied by a cursor, used to bound the SQL side of the read
     * path before scores are compared exactly in Java.
     */
    static long startMillisOf(double score) {
        return (long) score >> TIE_BITS;
    }
}
