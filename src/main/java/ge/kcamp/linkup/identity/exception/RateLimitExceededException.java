package ge.kcamp.linkup.identity.exception;

/**
 * Too many attempts at something limited per key. Carries the wait so the 429 can say
 * when to come back in {@code Retry-After}.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Too many sign-in attempts. Please wait a few minutes and try again.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
