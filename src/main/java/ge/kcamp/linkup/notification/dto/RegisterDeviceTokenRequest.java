package ge.kcamp.linkup.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceTokenRequest(
        @NotBlank String token,
        @NotBlank String platform
) {
}
