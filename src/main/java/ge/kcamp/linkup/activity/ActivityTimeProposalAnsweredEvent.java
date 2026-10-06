package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * The host answered a time suggestion, so its proposer is told. Part of the
 * {@code activity} module's event API.
 *
 * @param accepted true when the plan moved and the proposer is now in it
 * @param startTime the proposed start - the plan's new start when accepted
 */
public record ActivityTimeProposalAnsweredEvent(
        UUID activityId,
        UUID hostId,
        UUID proposerId,
        String title,
        boolean accepted,
        ZonedDateTime startTime,
        boolean hasTime,
        Instant occurredAt
) {
}
