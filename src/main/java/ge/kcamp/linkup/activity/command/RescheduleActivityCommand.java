package ge.kcamp.linkup.activity.command;

import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Moves a plan to another time and touches nothing else, unlike {@link UpdateActivityCommand},
 * which replaces the whole editable surface. Used when the host accepts a time suggestion.
 *
 * @param notTold people left out of the "plan updated" notice because they are told another
 *                way - the person whose suggestion this was gets "accepted" instead
 */
public record RescheduleActivityCommand(
        UUID activityId,
        UUID actorId,
        ZonedDateTime startTime,
        ZonedDateTime endTime,
        Set<UUID> notTold
) {
}
