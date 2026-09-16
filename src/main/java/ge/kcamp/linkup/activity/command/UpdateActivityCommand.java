package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * @param actorId  who is asking. Only the creator may edit; see
 *                 {@code ActivityCommandHandler.requireOwned}.
 * @param repeatFrequency null for a one-off plan; otherwise the plan repeats every
 *                        {@code repeatInterval} of that unit until {@code repeatUntil}
 *                        (null = no end date). Already normalised by the DTO: the
 *                        interval and the end date are null whenever the frequency is.
 * @param category null leaves the activity's current category unchanged, unlike the
 *                 rest of this command's fields, which fully replace it - a client that
 *                 doesn't yet know about categories shouldn't reset one back to GENERAL.
 */
public record UpdateActivityCommand(
        UUID activityId,
        UUID actorId,
        String title,
        ZonedDateTime startTime,
        ZonedDateTime endTime,
        boolean hasTime,
        Double lat,
        Double lng,
        String addressText,
        ActivityVisibility visibility,
        UUID groupId,
        ActivityCategory category,
        RepeatFrequency repeatFrequency,
        Integer repeatInterval,
        ZonedDateTime repeatUntil
) {
}
