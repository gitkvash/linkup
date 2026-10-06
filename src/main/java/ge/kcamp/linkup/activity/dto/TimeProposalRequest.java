package ge.kcamp.linkup.activity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.ZonedDateTime;

/**
 * Body of {@code POST /activities/{id}/time-proposals}.
 *
 * @param endTime optional. Left out, a plan that has an end keeps its length, moved with the
 *                new start.
 * @param message an optional word to the host ("work until 7"); blank is the same as none.
 */
public record TimeProposalRequest(
        @NotNull ZonedDateTime startTime,
        ZonedDateTime endTime,
        @Size(max = 200) String message
) {
}
