package ge.kcamp.linkup.identity.dto;

import java.util.UUID;

/**
 * @param newAccount true when this call created the account rather than signing an
 *                   existing one in. Google sign-in has to derive a username from the
 *                   email because it is never asked for one, so the client uses this to
 *                   offer the new user a chance to pick their own - once, on the way in,
 *                   rather than leaving them to discover "giorgik1" in their profile.
 */
public record AuthResponse(
        String token,
        UUID userId,
        String username,
        boolean newAccount
) {
}
