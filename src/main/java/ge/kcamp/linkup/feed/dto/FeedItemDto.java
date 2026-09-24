package ge.kcamp.linkup.feed.dto;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;

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
 * @param category        what kind of activity it is. The feed card is drawn per
 *                        category (band, pattern, token), so the "All" tab needs
 *                        it as much as "Mine" and "Participating" do.
 * @param hasTime         false when only a date was given - the card says Flexible.
 * @param activityType    casual plan or specific event, for the same Fixed/Flexible line.
 * @param repeatFrequency set when the plan repeats; the card names the cadence.
 * @param repeatInterval  "every N", paired with {@code repeatFrequency}.
 */
public record FeedItemDto(
        UUID activityId,
        UUID creatorId,
        String creatorUsername,
        String title,
        ZonedDateTime startTime,
        String addressText,
        ActivityStatus status,
        ActivityCategory category,
        boolean hasTime,
        ActivityType activityType,
        RepeatFrequency repeatFrequency,
        Integer repeatInterval
) {
}
