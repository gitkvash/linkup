package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when who is in a plan, or how it stands, changed without a notification of its
 * own: someone joined, left or answered an invitation, or the host ended it. Part of the
 * {@code activity} module's event API.
 * <p>
 * It carries no audience. The listener asks {@link ActivityParticipationService#audienceOf}
 * when it runs, so the people told are the ones in the plan <em>then</em>, and a leaver's
 * row that has just been deleted doesn't have to be reconstructed here.
 */
public record ActivityChangedEvent(
        UUID activityId,
        Instant occurredAt
) {
}
