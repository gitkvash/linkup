package ge.kcamp.linkup.social;

import java.util.UUID;

/**
 * An unanswered friend request.
 *
 * @param incoming true when the other party asked us (so we can accept or decline),
 *                 false when we asked them and are waiting.
 */
public record PendingFriendRequest(UUID userId, String username, boolean incoming) {
}
