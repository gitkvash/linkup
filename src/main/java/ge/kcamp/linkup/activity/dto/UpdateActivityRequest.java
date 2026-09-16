package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Body of {@code PATCH /api/v1/activities/{id}}. Every field is the new value - this is
 * a full replacement of the editable fields, not a sparse patch, because that is what
 * the edit form sends: it seeds itself from the current activity and posts the whole
 * form back.
 * <p>
 * Invitees are deliberately absent. Who is coming is changed through join/respond, and
 * accepting a list here would silently re-invite people who had already declined.
 *
 * @param hasTime  null (or omitted) means "not stated" - treated as {@code true}, same as
 *                 {@link CreateStructuredActivityRequest}.
 * @param repeatFrequency null (or omitted) means the plan happens once, which is what
 *                        every plan was before this field existed. When set, the plan
 *                        repeats every {@code repeatInterval} of that unit from
 *                        {@code startTime} until {@code repeatUntil}.
 * @param repeatInterval  "every N". Null alongside a frequency is read as 1; without a
 *                        frequency it is a leftover from a form the user changed their
 *                        mind on and is dropped, not rejected - see
 *                        {@code resolvedRepeatInterval()}.
 * @param repeatUntil     when the repetition stops, or null for no end date.
 * @param category null (or omitted) leaves the activity's current category unchanged -
 *                 see {@code UpdateActivityCommand}.
 */
public record UpdateActivityRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull ZonedDateTime startTime,
        ZonedDateTime endTime,
        Boolean hasTime,
        Double lat,
        Double lng,
        @Size(max = 255) String addressText,
        @NotNull ActivityVisibility visibility,
        UUID groupId,
        ActivityCategory category,
        RepeatFrequency repeatFrequency,
        @Min(1) @Max(52) Integer repeatInterval,
        ZonedDateTime repeatUntil
) {
    public boolean resolvedHasTime() {
        return hasTime == null || hasTime;
    }

    /** 1 when a frequency was given without one; null when there is no frequency. */
    public Integer resolvedRepeatInterval() {
        if (repeatFrequency == null) {
            return null;
        }
        return repeatInterval == null ? 1 : repeatInterval;
    }

    /** Dropped when there is no frequency for it to bound. */
    public ZonedDateTime resolvedRepeatUntil() {
        return repeatFrequency == null ? null : repeatUntil;
    }

    /**
     * A rule that ends before it starts describes no occurrences at all. Rejected rather
     * than normalised because, unlike a stray interval, it is a date the user picked on
     * purpose and silently dropping it would leave a plan repeating forever.
     */
    @AssertTrue(message = "repeatUntil must not be before startTime")
    public boolean isRepeatWindowOrdered() {
        if (repeatFrequency == null || repeatUntil == null || startTime == null) {
            return true;
        }
        return !repeatUntil.isBefore(startTime);
    }
}
