package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The host invited more people to a plan that already existed. Separate from
 * {@link ActivityCreatedEvent} because only the invitation follows from it - the plan
 * is not new, so it must not be fanned out into anyone's feed a second time.
 *
 * @param invitedUserIds only the people this call added. Anyone who was already invited,
 *                       going or had declined is left out, so nobody is notified twice.
 */
public record ActivityInvitationsSentEvent(
        UUID activityId,
        UUID inviterId,
        String title,
        ZonedDateTime startTime,
        List<UUID> invitedUserIds,
        Instant occurredAt
) {
}
