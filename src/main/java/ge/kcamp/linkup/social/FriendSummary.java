package ge.kcamp.linkup.social;

import java.util.UUID;

/**
 * A friend, with their name. {@code GET /friends} used to return bare
 * {@code List<UUID>}, which is why the friends list rendered raw UUIDs.
 */
public record FriendSummary(UUID userId, String username) {
}
