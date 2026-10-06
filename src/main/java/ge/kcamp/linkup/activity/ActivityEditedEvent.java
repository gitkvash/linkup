package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The host changed something the people in a plan would want to know about: its name, its
 * time or repeat schedule, or its place. Published by {@code ActivityCommandHandler}
 * alongside {@link ActivityUpdatedEvent}, which is for the feed and fires on every edit;
 * this one fires only when there is something to tell, and only for a plan that is not
 * over.
 *
 * @param previousTitle    the old name when it changed, otherwise null
 * @param scheduleChanged  the start, end, whether it has a clock time, or the repeat rule
 * @param startTime        the occurrence to show: the next one for a repeating plan
 * @param placeChanged     the address or the pin moved
 * @param addressText      the place's name after the edit, null when it has none
 * @param participantIds   everyone joined or still invited, the host excluded - captured
 *                         here because the listener runs later, on another thread
 */
public record ActivityEditedEvent(
        UUID activityId,
        UUID hostId,
        String title,
        String previousTitle,
        boolean scheduleChanged,
        ZonedDateTime startTime,
        boolean hasTime,
        boolean placeChanged,
        String addressText,
        List<UUID> participantIds,
        Instant occurredAt
) {
}
