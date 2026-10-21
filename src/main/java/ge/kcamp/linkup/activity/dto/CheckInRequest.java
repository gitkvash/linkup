package ge.kcamp.linkup.activity.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code POST /activities/{id}/check-in}: the host's answer to "did it happen?",
 * asked once a one-off nobody started is past its start (see
 * {@code ActivityStatusResolver.needsCheckIn}).
 */
public record CheckInRequest(@NotNull Boolean happened) {
}
