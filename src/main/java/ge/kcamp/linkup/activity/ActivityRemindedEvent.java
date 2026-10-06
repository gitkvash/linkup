package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The host pressed "Remind everyone" on their plan. Published by
 * {@link ActivityLifecycleService#remind}, and distinct from
 * {@link ActivityStartingSoonEvent}, which the scheduler publishes by itself.
 *
 * @param startTime      the occurrence the reminder is about - the next one, for a
 *                       repeating plan; null when the plan is already live
 * @param live           the plan is under way, so "starts at ..." would be wrong
 * @param participantIds everyone who joined, the host excluded
 * @param occurredAt     when the host pressed it. Part of the notification's dedupe key,
 *                       so a redelivery dedupes but a second press does not.
 */
public record ActivityRemindedEvent(
        UUID activityId,
        UUID hostId,
        String title,
        ZonedDateTime startTime,
        boolean hasTime,
        boolean live,
        List<UUID> participantIds,
        Instant occurredAt
) {
}
