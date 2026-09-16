package ge.kcamp.linkup.identity;

import java.util.UUID;

/**
 * The public shape of a user: enough to render them, and nothing more.
 * <p>
 * Deliberately not the {@code User} entity, which carries {@code passwordHash} —
 * returning that entity from a controller would serialize the hash to the client.
 */
public record UserSummary(UUID userId, String username, String displayName, String bio) {

    /**
     * The one name to render. Callers that show a person in a row, a card or a chip use
     * this rather than choosing for themselves - "which name goes here" is a question
     * with one answer, not one per screen.
     */
    public String name() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }
}
