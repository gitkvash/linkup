package ge.kcamp.linkup.nlp;

import java.time.ZoneId;

/**
 * Public API of the {@code nlp} module: extracts a title, time range, and (best-effort)
 * location text from a free-text activity description.
 */
public interface NlpParserService {

    ParsedActivityText parse(String rawText, ZoneId targetZone);
}
