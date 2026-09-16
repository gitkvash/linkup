package ge.kcamp.linkup.feed.dto;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * @param creatorUsername who made the plan. Carried alongside the id so a feed card
 *                        can name them: the client had only {@code creatorId} and no
 *                        way to resolve it, so it couldn't say whose plan it was.
 *                        Null if the account has since been removed.
 */
public record FeedItemDto(
        UUID activityId,
        UUID creatorId,
        String creatorUsername,
        String title,
        ZonedDateTime startTime,
        String addressText
) {
}
