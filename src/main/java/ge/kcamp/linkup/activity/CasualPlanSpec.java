package ge.kcamp.linkup.activity;

import java.time.ZonedDateTime;

/**
 * A loosely-defined plan (e.g. "coffee this evening") - has an approximate start time
 * and, at best, a free-text location description (no verified coordinates).
 */
public record CasualPlanSpec(
        String title,
        ZonedDateTime approximateStartTime,
        boolean hasTime,
        String locationText
) implements ActivitySpec {
}
