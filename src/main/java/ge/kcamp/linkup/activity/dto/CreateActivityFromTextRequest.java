package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param timeZone IANA zone id (e.g. {@code Asia/Tbilisi}) the free text should be read
 *                 in. Optional for now so already-installed clients keep working - they
 *                 fall back to UTC, which is the old behaviour. Once the shipped client
 *                 always sends it, this becomes {@code @NotBlank}.
 */
public record CreateActivityFromTextRequest(
        @NotBlank @Size(max = 500) String rawText,
        @NotNull ActivityVisibility visibility,
        UUID groupId,
        List<UUID> inviteeUserIds,
        String timeZone
) {
}
