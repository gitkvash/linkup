package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * A guest said no to a plan, so its host is told. Part of the {@code activity} module's
 * event API.
 *
 * @param reason what the guest said, trimmed, or null if they gave none
 */
public record ActivityInvitationDeclinedEvent(
        UUID activityId,
        UUID hostId,
        UUID declinerId,
        String title,
        String reason,
        Instant occurredAt
) {
}
