package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A one-off's host never started it, and its {@link ActivityStatusResolver#START_GRACE}
 * has run out: the host is asked whether it happened. Published by
 * {@link ActivityReminderScheduler}; for the host alone.
 * <p>
 * Unanswered for {@link ActivityStatusResolver#CHECK_IN_WINDOW} the plan reads as
 * cancelled, and the copy says so.
 *
 * @param startTime  when the plan was for
 * @param occurredAt the plan's start, so a scan that picks it up twice dedupes, and a
 *                   plan moved to another time is asked about again
 */
public record ActivityCheckInDueEvent(
        UUID activityId,
        UUID hostId,
        String title,
        ZonedDateTime startTime,
        Instant occurredAt
) {
}
