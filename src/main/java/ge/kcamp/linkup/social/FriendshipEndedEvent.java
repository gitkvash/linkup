package ge.kcamp.linkup.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when an accepted friendship ends - unfriended, or replaced by a block. Part of
 * the {@code social} module's event API: the counterpart of {@link FriendshipAcceptedEvent},
 * so the feed can take back what that one backfilled.
 */
public record FriendshipEndedEvent(
        UUID userAId,
        UUID userBId,
        Instant occurredAt
) {
}
