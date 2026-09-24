package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One occurrence of a plan is about to start. Published by {@link ActivityReminderScheduler}.
 *
 * @param startTime      when this occurrence starts - for a repeating plan, not the
 *                       row's first start time
 * @param participantIds everyone who joined, the host included
 * @param occurredAt     the occurrence's start, not when the scan ran. It is what makes
 *                       a reminder dedupe: every scan that sees the same occurrence -
 *                       after a restart, or on a second instance during a deploy -
 *                       produces the same notification key, so it is sent once.
 */
public record ActivityStartingSoonEvent(
        UUID activityId,
        String title,
        ZonedDateTime startTime,
        List<UUID> participantIds,
        Instant occurredAt
) {
}
