package ge.kcamp.linkup.nlp;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Every text parser is busy. Part of the module's public API because callers (and the
 * global exception handler) need to distinguish "temporarily at capacity" from a real
 * failure: it maps to 503, so the client can retry or use the structured create form.
 * <p>
 * {@code GlobalExceptionHandler} maps it explicitly (with an error code); the
 * annotation is the fallback, so a handler change can't quietly turn parser exhaustion
 * back into a 500.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class NlpUnavailableException extends RuntimeException {

    public NlpUnavailableException() {
        super("Too many descriptions are being processed right now. Please try again.");
    }
}
