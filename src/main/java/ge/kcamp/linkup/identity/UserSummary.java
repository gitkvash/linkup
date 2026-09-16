package ge.kcamp.linkup.identity;

import java.util.UUID;

/**
 * The public shape of a user: enough to render them, and nothing more.
 * <p>
 * Deliberately not the {@code User} entity, which carries {@code passwordHash} —
 * returning that entity from a controller would serialize the hash to the client.
 */
public record UserSummary(UUID userId, String username) {
}
