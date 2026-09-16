package ge.kcamp.linkup.nlp.internal;

import com.zoho.hawking.datetimeparser.configuration.HawkingConfiguration;
import com.zoho.hawking.language.english.model.DateRange;
import com.zoho.hawking.language.english.model.DatesFound;
import com.zoho.hawking.language.english.model.ParserOutput;
import ge.kcamp.linkup.nlp.ParsedActivityText;
import org.joda.time.DateTime;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Latin-script parsing, backed by Hawking (Stanford CoreNLP).
 */
@Component
class EnglishNlpParser {

    /**
     * Best-effort location heuristic: "at/in <place>". Hawking extracts dates and times
     * only, not locations - this is a placeholder, not named-entity recognition.
     * <p>
     * Case-insensitive on the first letter: the previous pattern required a capital, so
     * "coffee at vake park" - how people actually type - matched nothing.
     */
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "\\b(?:at|in)\\s+([\\p{L}][\\p{L}0-9&'.-]*(?:\\s+[\\p{L}][\\p{L}0-9&'.-]*){0,3})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRAILING_CONNECTOR = Pattern.compile("(?i)\\b(at|on|in|for|by|to|with)\\s*$");
    private static final Pattern EXTRA_WHITESPACE = Pattern.compile("\\s{2,}");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[,\\-\\s]+|[,\\-\\s]+$");

    private final HawkingParserPool parserPool;

    EnglishNlpParser(HawkingParserPool parserPool) {
        this.parserPool = parserPool;
    }

    ParsedActivityText parse(String rawText, ZoneId targetZone) {
        HawkingConfiguration config = new HawkingConfiguration();
        config.setTimeZone(targetZone.getId());

        DatesFound result = parserPool.withParser(
                parser -> parser.parse(rawText, Date.from(Instant.now()), config, "eng"));
        List<ParserOutput> outputs = result == null ? null : result.getParserOutputs();

        if (outputs == null || outputs.isEmpty()) {
            return withoutTime(rawText, targetZone);
        }

        ParserOutput best = outputs.stream()
                .filter(o -> Boolean.TRUE.equals(o.getIsExactTimePresent()))
                .findFirst()
                .orElse(outputs.get(0));

        DateRange range = best.getDateRange();
        Optional<ZonedDateTime> startTime =
                Optional.ofNullable(range).map(DateRange::getStart).map(instant -> atZone(instant, targetZone));
        Optional<ZonedDateTime> endTime =
                Optional.ofNullable(range).map(DateRange::getEnd).map(instant -> atZone(instant, targetZone));

        String remainingText = stripMatchedPhrase(rawText, best);
        LocationMatch location = extractLocation(remainingText);

        String title = cleanTitle(location == null
                ? remainingText
                : remove(remainingText, location.start(), location.end()));

        boolean hasExplicitTime = Boolean.TRUE.equals(best.getIsExactTimePresent());
        double confidence = hasExplicitTime ? 1.0 : (range != null ? 0.5 : 0.0);

        return new ParsedActivityText(
                title, startTime, endTime,
                location == null ? Optional.empty() : Optional.of(location.text()),
                true, confidence, hasExplicitTime);
    }

    private ParsedActivityText withoutTime(String rawText, ZoneId zone) {
        LocationMatch location = extractLocation(rawText);
        String title = cleanTitle(location == null
                ? rawText
                : remove(rawText, location.start(), location.end()));
        return new ParsedActivityText(
                title, Optional.empty(), Optional.empty(),
                location == null ? Optional.empty() : Optional.of(location.text()),
                false, 0.0, false);
    }

    /**
     * Keeps the instant but presents it in the caller's zone. This used to force
     * {@code ZoneOffset.UTC}, discarding the offset Hawking had resolved, so the zone the
     * user meant was unrecoverable downstream.
     */
    private ZonedDateTime atZone(DateTime jodaDateTime, ZoneId zone) {
        return Instant.ofEpochMilli(jodaDateTime.getMillis()).atZone(zone);
    }

    private String stripMatchedPhrase(String rawText, ParserOutput output) {
        Integer start = output.getParserStartIndex();
        Integer end = output.getParserEndIndex();
        if (start != null && end != null && start >= 0 && end <= rawText.length() && start < end) {
            return remove(rawText, start, end);
        }
        // Fall back to removing only the first occurrence: replace() removed every one,
        // so "lunch at 1 at the cafe at 1pm" lost more than the matched phrase.
        String text = output.getText();
        if (text != null && !text.isEmpty()) {
            int index = rawText.indexOf(text);
            if (index >= 0) {
                return remove(rawText, index, index + text.length());
            }
        }
        return rawText;
    }

    private LocationMatch extractLocation(String text) {
        Matcher matcher = LOCATION_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return new LocationMatch(matcher.group(1).trim(), matcher.start(), matcher.end());
    }

    private static String remove(String text, int start, int end) {
        if (start < 0 || end > text.length() || start >= end) {
            return text;
        }
        return text.substring(0, start) + " " + text.substring(end);
    }

    private String cleanTitle(String text) {
        String cleaned = TRAILING_CONNECTOR.matcher(text).replaceAll("");
        cleaned = EXTRA_WHITESPACE.matcher(cleaned).replaceAll(" ");
        cleaned = EDGE_PUNCTUATION.matcher(cleaned).replaceAll("");
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? text.trim() : cleaned;
    }

    private record LocationMatch(String text, int start, int end) {
    }
}
