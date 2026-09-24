package ge.kcamp.linkup.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The username rule is {@link UpdateProfileRequest}'s, and has to stay identical. Without
 * it registration accepted what a rename refused: a trailing space ("alice " beside
 * "alice"), control characters, and look-alike letters from other scripts - an account
 * that searches and renders as someone else's.
 */
public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(
                regexp = "^[A-Za-z0-9._-]+$",
                message = "A username can only contain letters, numbers, dots, dashes and underscores")
        String username,
        @NotBlank @Size(min = 8, max = 255) String password
) {
}
