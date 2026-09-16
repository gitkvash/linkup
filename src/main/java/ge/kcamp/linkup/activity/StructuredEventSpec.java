package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.RepeatFrequency;

import java.time.ZonedDateTime;

/**
 * A concrete event with an exact start time and (optionally) verified coordinates.
 *
 * @param repeatFrequency null for a one-off plan. When set, the plan comes round every
 *                        {@code repeatInterval} of this unit; nothing is materialised
 *                        per occurrence (see {@code V24__activity_recurrence.sql}).
 * @param repeatInterval  "every N" - only meaningful alongside a frequency.
 * @param repeatUntil     when the repetition stops, or null for no end date.
 */
public record StructuredEventSpec(
        String title,
        ZonedDateTime startTime,
        ZonedDateTime endTime,
        boolean hasTime,
        String locationText,
        Double lat,
        Double lng,
        RepeatFrequency repeatFrequency,
        Integer repeatInterval,
        ZonedDateTime repeatUntil
) implements ActivitySpec {
}
