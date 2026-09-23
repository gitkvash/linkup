package ge.kcamp.linkup.feed.dto;

import ge.kcamp.linkup.activity.enums.ActivityStatus;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * @param creatorUsername who made the plan. Carried alongside the id so a feed card
 *                        can name them: the client had only {@code creatorId} and no
 *                        way to resolve it, so it couldn't say whose plan it was.
 *                        Null if the account has since been removed.
 * @param status          where the plan is in its own life - upcoming, live or over.
 *                        The same field {@code ActivityFeedItem} carries, for the same
 *                        card: without it the feed's "All" tab had to guess from
 *                        {@code startTime}, and a plan that started twenty minutes ago
 *                        and is happening right now reads as over to a clock. Derived,
 *                        never stored - see {@code ActivityStatusResolver}.
 */
public record FeedItemDto(
        UUID activityId,
        UUID creatorId,
        String creatorUsername,
        String title,
        ZonedDateTime startTime,
        String addressText,
        ActivityStatus status
) {
}
