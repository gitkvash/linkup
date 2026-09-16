package ge.kcamp.linkup.social.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GroupMemberRequest(
        @NotNull UUID userId
) {
}
