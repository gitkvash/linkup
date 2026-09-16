package ge.kcamp.linkup.nlp.internal;

import ge.kcamp.linkup.nlp.ParsedActivityText;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based parser for Georgian free text.
 * <p>
 * Hawking ships no Georgian model, and the service always asked it for {@code "eng"}, so
 * Georgian input found no time at all: the caller then silently defaulted the activity to
 * "one hour from now", and the title kept the whole raw sentence including the date
 * phrase. Georgian is this app's primary language, so that was the common case, not an
 * edge case.
 * <p>
 * Deliberately narrow: relative day words, weekday names, and clock times of the forms
 * Georgian actually uses ("18:30", "6 საათზე", "საღამოს 7-ზე"). Anything it can't read is
 * reported as not-found with zero confidence rather than guessed at.
 */
@Component
class GeorgianTimeExpressionParser {

    /** Any character in the Georgian Unicode block. */
    private static final Pattern GEORGIAN_SCRIPT = Pattern.compile("\\p{IsGeorgian}");

    /** Relative day words, longest first so "ზეგ" doesn't shadow a longer match. */
    private static final Map<String, Integer> RELATIVE_DAYS = new LinkedHashMap<>();

    /** Weekday names in the adverbial form used for "on <day>". */
    private static final Map<String, DayOfWeek> WEEKDAYS = new LinkedHashMap<>();

    /** Parts of day, used when a bare hour needs disambiguating. */
    private static final Map<String, Integer> DAY_PARTS = new LinkedHashMap<>();

    static {
        RELATIVE_DAYS.put("ზეგ", 2);          // day after tomorrow
        RELATIVE_DAYS.put("ხვალ", 1);          // tomorrow
        RELATIVE_DAYS.put("დღეს", 0);          // today
        RELATIVE_DAYS.put("ამაღამ", 0);        // tonight

        WEEKDAYS.put("ორშაბათს", DayOfWeek.MONDAY);
        WEEKDAYS.put("სამშაბათს", DayOfWeek.TUESDAY);
        WEEKDAYS.put("ოთხშაბათს", DayOfWeek.WEDNESDAY);
        WEEKDAYS.put("ხუთშაბათს", DayOfWeek.THURSDAY);
        WEEKDAYS.put("პარასკევს", DayOfWeek.FRIDAY);
        WEEKDAYS.put("შაბათს", DayOfWeek.SATURDAY);
        WEEKDAYS.put("კვირას", DayOfWeek.SUNDAY);

        DAY_PARTS.put("დილის", 8);            // morning
        DAY_PARTS.put("შუადღის", 13);          // midday
        DAY_PARTS.put("საღამოს", 19);          // evening
        DAY_PARTS.put("ღამის", 22);            // night
    }

    // UNICODE_CHARACTER_CLASS matters on every pattern below that puts \b next to
    // Georgian text: by default \b is defined in terms of [a-zA-Z0-9_], so a Georgian
    // letter counts as a non-word character and "18 საათზე" had no word boundary after
    // the suffix - the pattern simply never matched.
    private static final int UNICODE = Pattern.UNICODE_CHARACTER_CLASS;

    /** "18:30" or "18.30". */
    private static final Pattern CLOCK_TIME =
            Pattern.compile("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b", UNICODE);

    /**
     * A bare hour marked as a time: "6 საათზე", "7-ზე", "19 საათზე". The locative suffix
     * is what distinguishes an hour from any other number in the sentence.
     */
    private static final Pattern HOUR_WITH_SUFFIX =
            Pattern.compile("\\b([01]?\\d|2[0-3])\\s*(?:საათ)?-?(?:ზე|ს)\\b", UNICODE);

    /**
     * Locative place phrase: a Georgian word carrying the "-ში" (in) or "-ზე" (at/on)
     * suffix, optionally preceded by a modifier - "ვაკის პარკში", "ყავაზე".
     */
    private static final Pattern GEORGIAN_PLACE =
            Pattern.compile("\\b(\\p{IsGeorgian}+(?:\\s+\\p{IsGeorgian}+)?(?:ში|ზე))\\b", UNICODE);

    private static final Pattern EXTRA_WHITESPACE = Pattern.compile("\\s{2,}");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[,\\-\\s]+|[,\\-\\s]+$");

    /** True when the text is (at least partly) Georgian script. */
    static boolean handles(String text) {
        return text != null && GEORGIAN_SCRIPT.matcher(text).find();
    }

    ParsedActivityText parse(String rawText, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        String working = rawText;

        DayMatch day = matchDay(working, now);
        if (day != null) {
            working = remove(working, day.start(), day.end());
        }

        TimeMatch time = matchTime(working);
        if (time != null) {
            working = remove(working, time.start(), time.end());
        }

        Optional<String> place = Optional.empty();
        Matcher placeMatcher = GEORGIAN_PLACE.matcher(working);
        if (placeMatcher.find()) {
            place = Optional.of(placeMatcher.group(1).trim());
            working = remove(working, placeMatcher.start(), placeMatcher.end());
        }

        Optional<ZonedDateTime> startTime = resolveStart(day, time, now);
        String title = clean(working);
        if (title.isEmpty()) {
            title = clean(rawText);
        }

        double confidence;
        if (day != null && time != null) {
            confidence = 1.0;
        } else if (day != null || time != null) {
            confidence = 0.5;
        } else {
            confidence = 0.0;
        }

        return new ParsedActivityText(
                title, startTime, Optional.empty(), place, startTime.isPresent(), confidence, time != null);
    }

    private Optional<ZonedDateTime> resolveStart(DayMatch day, TimeMatch time, ZonedDateTime now) {
        if (day == null && time == null) {
            return Optional.empty();
        }

        ZonedDateTime date = day == null ? now : day.date();
        LocalTime at = time == null
                // A day with no time: default to a sociable evening hour rather than
                // midnight, which would read as "already over".
                ? LocalTime.of(19, 0)
                : time.time();

        ZonedDateTime resolved = date.with(at);

        // "6 საათზე" with no day, already past today, means tomorrow.
        if (day == null && resolved.isBefore(now)) {
            resolved = resolved.plusDays(1);
        }
        return Optional.of(resolved);
    }

    private DayMatch matchDay(String text, ZonedDateTime now) {
        for (Map.Entry<String, Integer> entry : RELATIVE_DAYS.entrySet()) {
            int index = text.indexOf(entry.getKey());
            if (index >= 0) {
                return new DayMatch(
                        now.plusDays(entry.getValue()), index, index + entry.getKey().length());
            }
        }
        for (Map.Entry<String, DayOfWeek> entry : WEEKDAYS.entrySet()) {
            int index = text.indexOf(entry.getKey());
            if (index >= 0) {
                ZonedDateTime next = now.with(TemporalAdjusters.next(entry.getValue()));
                return new DayMatch(next, index, index + entry.getKey().length());
            }
        }
        return null;
    }

    private TimeMatch matchTime(String text) {
        Matcher clock = CLOCK_TIME.matcher(text);
        if (clock.find()) {
            return new TimeMatch(
                    LocalTime.of(Integer.parseInt(clock.group(1)), Integer.parseInt(clock.group(2))),
                    clock.start(), clock.end());
        }

        Matcher hour = HOUR_WITH_SUFFIX.matcher(text);
        if (hour.find()) {
            int value = Integer.parseInt(hour.group(1));
            return new TimeMatch(LocalTime.of(shiftForDayPart(text, value), 0), hour.start(), hour.end());
        }

        for (Map.Entry<String, Integer> entry : DAY_PARTS.entrySet()) {
            int index = text.indexOf(entry.getKey());
            if (index >= 0) {
                return new TimeMatch(
                        LocalTime.of(entry.getValue(), 0), index, index + entry.getKey().length());
            }
        }
        return null;
    }

    /** "საღამოს 7" is 19:00, not 07:00. */
    private int shiftForDayPart(String text, int hour) {
        if (hour >= 12 || hour == 0) {
            return hour;
        }
        boolean afternoon = text.contains("საღამოს") || text.contains("ღამის") || text.contains("შუადღის");
        return afternoon ? hour + 12 : hour;
    }

    private static String remove(String text, int start, int end) {
        if (start < 0 || end > text.length() || start >= end) {
            return text;
        }
        return text.substring(0, start) + " " + text.substring(end);
    }

    private static String clean(String text) {
        String cleaned = EXTRA_WHITESPACE.matcher(text).replaceAll(" ");
        cleaned = EDGE_PUNCTUATION.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    private record DayMatch(ZonedDateTime date, int start, int end) {
    }

    private record TimeMatch(LocalTime time, int start, int end) {
    }
}
