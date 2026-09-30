package ge.kcamp.linkup.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sets the address reset codes go to. There is no clearing it: without one the account cannot recover. */
public record UpdateEmailRequest(
        @NotBlank @Email @Size(max = 254) String email
) {
}
