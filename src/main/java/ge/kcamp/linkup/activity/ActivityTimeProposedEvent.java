package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Someone in a plan suggested another time, so the host is told. Part of the
 * {@code activity} module's event API.
 *
 * @param currentStart  the plan's start when it was suggested, so the notification can say
 *                      what it would change
 * @param proposedStart the suggested start
 * @param hasTime       false when the plan is a date only; the notification then names
 *                      days, not clock times
 * @param occurredAt    part of the notification's dedupe key, so a redelivery dedupes but a
 *                      second suggestion does not
 */
public record ActivityTimeProposedEvent(
        UUID activityId,
        UUID hostId,
        UUID proposerId,
        String title,
        ZonedDateTime currentStart,
        ZonedDateTime proposedStart,
        boolean hasTime,
        String message,
        Instant occurredAt
) {
}
