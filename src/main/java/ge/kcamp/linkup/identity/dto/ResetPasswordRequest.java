package ge.kcamp.linkup.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The password rule is {@link RegisterRequest}'s and has to stay identical: a reset must not
 * accept what registration refuses.
 */
public record ResetPasswordRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "The code is 6 digits") String code,
        @NotBlank @Size(min = 8, max = 255) String newPassword
) {
}
