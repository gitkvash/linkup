package ge.kcamp.linkup.identity.dto;

import java.util.UUID;

/**
 * @param newAccount true when this call created the account rather than signing an
 *                   existing one in. Google sign-in is never asked for a username and
 *                   gives the account a random placeholder, so the client uses this to
 *                   offer the new user a chance to pick their own - once, on the way in,
 *                   rather than leaving them to discover "user4k2x9q7m" in their profile.
 * @param refreshToken long-lived, and only accepted by {@code POST /auth/refresh} and
 *                     {@code /auth/logout}. The client trades it for a new pair when
 *                     {@code token} expires, which is what keeps someone signed in for
 *                     months rather than minutes. Single use: the refresh that accepts it
 *                     retires it, so the client must store the one that comes back.
 */
public record AuthResponse(
        String token,
        String refreshToken,
        UUID userId,
        String username,
        boolean newAccount
) {
}
