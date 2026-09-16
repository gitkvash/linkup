package ge.kcamp.linkup.nlp;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Result of parsing a free-text activity description.
 * {@code confidence} is a coarse heuristic (1.0 = exact time matched, 0.5 = a date-only
 * match, 0.0 = no time found at all) - it is NOT a calibrated ML confidence score.
 *
 * @param hasExplicitTime true when the text named an actual clock time (not just a day),
 *                        e.g. "6pm" or "18:30" - as opposed to a day-only match like
 *                        "tomorrow", where any hour attached to {@link #startTime} was
 *                        guessed rather than read from the text.
 */
public record ParsedActivityText(
        String title,
        Optional<ZonedDateTime> startTime,
        Optional<ZonedDateTime> endTime,
        Optional<String> locationText,
        boolean timeFound,
        double confidence,
        boolean hasExplicitTime
) {
}
