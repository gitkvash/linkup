package ge.kcamp.linkup.nlp.internal;

import ge.kcamp.linkup.nlp.NlpParserService;
import ge.kcamp.linkup.nlp.NlpUnavailableException;
import ge.kcamp.linkup.nlp.ParsedActivityText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Optional;

/**
 * The {@code nlp} module's single entry point: routes text to a parser that can actually
 * read it. Georgian went to an English-only model before, which never found a time.
 */
@Service
class CompositeNlpParserService implements NlpParserService {

    private static final Logger log = LoggerFactory.getLogger(CompositeNlpParserService.class);

    private final EnglishNlpParser englishParser;
    private final GeorgianTimeExpressionParser georgianParser;

    CompositeNlpParserService(EnglishNlpParser englishParser, GeorgianTimeExpressionParser georgianParser) {
        this.englishParser = englishParser;
        this.georgianParser = georgianParser;
    }

    @Override
    public ParsedActivityText parse(String rawText, ZoneId targetZone) {
        if (rawText == null || rawText.isBlank()) {
            return new ParsedActivityText(
                    "", Optional.empty(), Optional.empty(), Optional.empty(), false, 0.0, false);
        }

        if (GeorgianTimeExpressionParser.handles(rawText)) {
            return georgianParser.parse(rawText, targetZone);
        }

        try {
            return englishParser.parse(rawText, targetZone);
        } catch (NlpUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // CoreNLP throws on all sorts of input. A failed parse should cost the user
            // their times, not their whole request - the caller falls back to a default
            // start time and the raw text as the title.
            log.warn("English parse failed, falling back to raw text: {}", e.getMessage());
            return new ParsedActivityText(
                    rawText.strip(), Optional.empty(), Optional.empty(), Optional.empty(), false, 0.0, false);
        }
    }
}
