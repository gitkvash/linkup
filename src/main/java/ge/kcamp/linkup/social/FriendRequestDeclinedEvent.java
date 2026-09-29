package ge.kcamp.linkup.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a pending friend request is declined. Part of the {@code social}
 * module's event API. Deliberately not a notification: the requester is not told they
 * were turned down, but the request they sent has to stop showing as pending.
 */
public record FriendRequestDeclinedEvent(
        UUID requesterId,
        UUID declinerId,
        Instant occurredAt
) {
}
