package ge.kcamp.linkup.social.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FriendRequestDto(
        @NotNull UUID targetUserId
) {
}
