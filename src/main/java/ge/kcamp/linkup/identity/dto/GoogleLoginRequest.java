package ge.kcamp.linkup.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param termsVersion what the person agreed to, or null when they have not been asked. Only
 *        a sign-in that would create an account needs it: the server answers 428
 *        {@code TERMS_REQUIRED} and creates nothing until it is present and current.
 */
public record GoogleLoginRequest(@NotBlank String idToken, @Size(max = 20) String termsVersion) {
}
