package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after an activity is deleted. Part of the {@code activity} module's event
 * API: the feed drops it from the timelines it was fanned out to, which otherwise keep
 * the id until it ages out.
 */
public record ActivityDeletedEvent(
        UUID activityId,
        UUID creatorId,
        Instant occurredAt
) {
}
