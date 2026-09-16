package ge.kcamp.linkup.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a friend request is accepted. Part of the {@code social} module's
 * event API.
 */
public record FriendshipAcceptedEvent(
        UUID userAId,
        UUID userBId,
        Instant occurredAt
) {
}
