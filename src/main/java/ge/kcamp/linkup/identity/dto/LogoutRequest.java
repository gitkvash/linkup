package ge.kcamp.linkup.identity.dto;

/**
 * Not validated: sign-out answers 204 whatever it is sent, so a missing or garbled token
 * is not an error the client has to handle on its way out.
 */
public record LogoutRequest(String refreshToken) {
}
