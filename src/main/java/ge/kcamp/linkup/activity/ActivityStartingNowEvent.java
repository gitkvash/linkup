package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One occurrence of a plan has reached its start time and its host hasn't started it yet.
 * Published by {@link ActivityReminderScheduler}, a little after the thirty-minute
 * {@link ActivityStartingSoonEvent}.
 * <p>
 * A plan no longer starts by itself, so this is also the host's prompt: they are the one
 * who has to press start, and the plan is cancelled if nobody does within
 * {@link ActivityStatusResolver#START_GRACE}.
 *
 * @param hostId         so the host's copy can say what only they can do about it
 * @param startTime      when this occurrence starts - for a repeating plan, not the
 *                       row's first start time
 * @param participantIds everyone who joined, the host included
 * @param occurredAt     the occurrence's start, for the same dedupe reason
 *                       {@link ActivityStartingSoonEvent} gives
 */
public record ActivityStartingNowEvent(
        UUID activityId,
        UUID hostId,
        String title,
        ZonedDateTime startTime,
        List<UUID> participantIds,
        Instant occurredAt
) {
}
