package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @param repeatFrequency null for a one-off plan; otherwise the plan repeats every
 *                        {@code repeatInterval} of that unit until {@code repeatUntil}
 *                        (null = no end date). Already normalised by the DTO: the
 *                        interval and the end date are null whenever the frequency is.
 */
public record CreateStructuredActivityCommand(
        UUID creatorId,
        String title,
        ZonedDateTime startTime,
        ZonedDateTime endTime,
        boolean hasTime,
        Double lat,
        Double lng,
        String addressText,
        ActivityVisibility visibility,
        UUID groupId,
        List<UUID> inviteeUserIds,
        ActivityCategory category,
        RepeatFrequency repeatFrequency,
        Integer repeatInterval,
        ZonedDateTime repeatUntil
) {
}
