package ge.kcamp.linkup.feed;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Published once a fan-out or backfill has written to these users' timelines. Part of the
 * {@code feed} module's event API.
 * <p>
 * The people it names had no other way to learn: a friend's new plan is an invitation
 * only for whoever was invited by name, so everyone else's feed changed with nothing to
 * say so until they pulled to refresh. It is published <em>after</em> the Redis write,
 * which is the point of having it - a signal sent by anything that raced the write could
 * reach a client that then re-read a feed without the plan.
 */
public record FeedTimelinesUpdatedEvent(
        Set<UUID> userIds,
        Instant occurredAt
) {
}
