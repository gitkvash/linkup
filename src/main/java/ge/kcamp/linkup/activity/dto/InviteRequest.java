package ge.kcamp.linkup.activity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Body for {@code POST /activities/{id}/invites}: friends of the host to invite. */
public record InviteRequest(@NotEmpty @Size(max = 100) List<@NotNull UUID> userIds) {
}
