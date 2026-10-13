package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.ActivityVisibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published after an activity is deleted. Part of the {@code activity} module's event
 * API: the feed drops it from the timelines it was fanned out to, which otherwise keep
 * the id until it ages out.
 * <p>
 * Carries the plan's audience as it stood, since the row, its participants and its group
 * link are gone by the time the listener runs ({@code ON DELETE CASCADE}).
 *
 * @param visibility     null only on an event recorded before this field existed
 * @param groupId        the group a GROUP-visibility plan was shared with, otherwise null
 * @param participantIds everyone who had a participant row, whatever its status
 */
public record ActivityDeletedEvent(
        UUID activityId,
        UUID creatorId,
        ActivityVisibility visibility,
        UUID groupId,
        List<UUID> participantIds,
        Instant occurredAt
) {
}
