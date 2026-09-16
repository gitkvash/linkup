package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.activity.enums.ActivityVisibility;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * @param zone the caller's own time zone, in which relative phrases like "tomorrow at
 *             7pm" are interpreted. The server used to hardcode UTC, so that phrase
 *             created an activity at 23:00 for a user in Tbilisi.
 */
public record CreateActivityFromTextCommand(
        UUID creatorId,
        String rawText,
        ActivityVisibility visibility,
        UUID groupId,
        List<UUID> inviteeUserIds,
        ZoneId zone
) {
}
