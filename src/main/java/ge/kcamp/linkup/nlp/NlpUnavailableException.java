package ge.kcamp.linkup.nlp;

/**
 * Every text parser is busy. Part of the module's public API because callers (and the
 * global exception handler) need to distinguish "temporarily at capacity" from a real
 * failure: it maps to 503, so the client can retry or use the structured create form.
 */
public class NlpUnavailableException extends RuntimeException {

    public NlpUnavailableException() {
        super("Too many descriptions are being processed right now. Please try again.");
    }
}
