package ge.kcamp.linkup.identity.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a person may change about themselves. Every field is optional and a null means
 * "leave it alone" - the edit form sends the whole shape, and a profile with no bio must
 * not become a profile that cannot keep one.
 * <p>
 * Clearing is therefore an empty string, not null, and {@link #blankToNull} is what turns
 * it back into an absent value on the way to the column. The username is the exception:
 * it cannot be cleared, because it is how everyone else finds this account.
 */
public record UpdateProfileRequest(
        @Size(min = 3, max = 50)
        @Pattern(
                regexp = "^[A-Za-z0-9._-]+$",
                message = "A username can only contain letters, numbers, dots, dashes and underscores")
        String username,

        @Size(max = 50) String displayName,
        @Size(max = 160) String bio
) {
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
