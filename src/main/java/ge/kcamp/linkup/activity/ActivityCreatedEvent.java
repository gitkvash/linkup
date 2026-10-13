package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.ActivityVisibility;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published (via {@link org.springframework.context.ApplicationEventPublisher}) after an
 * activity is persisted. This is the {@code activity} module's event API - other modules
 * (notification, feed) react to it without depending on activity's internals.
 *
 * @param groupId the group a {@code GROUP}-visibility plan was shared with, null for any
 *                other visibility. Carried so feed fan-out can reach that group's
 *                members: fan-out otherwise only walks the creator's friends, and a group
 *                exists precisely so people who are not all friends can plan together.
 * @param visibility who may see the plan. Feed fan-out needs it: a PRIVATE plan goes
 *                   to its creator and invitees only, not to every friend - who would
 *                   have had it filtered back out on read, but were still woken by a
 *                   sync signal for it. Null only on an event recorded before this field
 *                   existed and redelivered from the publication log.
 */
public record ActivityCreatedEvent(
        UUID activityId,
        UUID creatorId,
        String title,
        ZonedDateTime startTime,
        List<UUID> invitedUserIds,
        UUID groupId,
        ActivityVisibility visibility,
        Instant occurredAt
) {
}
