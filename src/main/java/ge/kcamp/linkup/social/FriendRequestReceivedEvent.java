package ge.kcamp.linkup.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when someone asks to be friends.
 * <p>
 * Only {@code FriendshipAcceptedEvent} existed, so the person being asked was never
 * told: a request sat invisible until they happened to look. This is what makes a
 * friend-request inbox arrive rather than have to be discovered.
 */
public record FriendRequestReceivedEvent(
        UUID requesterId, UUID recipientId, Instant occurredAt) {
}
