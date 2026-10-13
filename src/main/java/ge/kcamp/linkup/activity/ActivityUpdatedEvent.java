package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.ActivityVisibility;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published after an activity is edited. Part of the {@code activity} module's event API.
 * <p>
 * The feed scores a timeline entry by start time, so an edit that moves the start leaves
 * the stored score and the one the cursor is computed from disagreeing - the plan is
 * skipped or repeated at a page boundary until the entry is re-scored.
 *
 * @param groupId the group a {@code GROUP}-visibility plan is shared with after the edit,
 *                null for any other visibility - the same meaning as on
 *                {@link ActivityCreatedEvent}.
 * @param visibility     who may see the plan after the edit; null only on an event
 *                       recorded before this field existed
 * @param participantIds everyone with a participant row, whatever its status - a PRIVATE
 *                       plan's whole audience besides the host. Captured here because the
 *                       listener runs later, on another thread.
 */
public record ActivityUpdatedEvent(
        UUID activityId,
        UUID creatorId,
        ZonedDateTime startTime,
        UUID groupId,
        ActivityVisibility visibility,
        List<UUID> participantIds,
        Instant occurredAt
) {
}
