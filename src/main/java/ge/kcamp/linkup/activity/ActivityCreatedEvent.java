package ge.kcamp.linkup.activity;

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
 */
public record ActivityCreatedEvent(
        UUID activityId,
        UUID creatorId,
        String title,
        ZonedDateTime startTime,
        List<UUID> invitedUserIds,
        UUID groupId,
        Instant occurredAt
) {
}
