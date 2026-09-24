package ge.kcamp.linkup.identity.security;

import ge.kcamp.linkup.identity.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Caps password attempts per account, independently of where they come from. The per-IP
 * limit in {@link RateLimitFilter} stops one client hammering; this stops a guessing run
 * spread across many addresses from focusing on one person.
 * <p>
 * Counted in the login itself rather than in a filter, because the username is in the
 * body and a filter that read it would leave nothing for the controller. Every attempt
 * counts, successful or not, and an unknown username is counted exactly like a real one -
 * otherwise the 429 itself would say which accounts exist.
 * <p>
 * Per instance, like {@link RateLimiter}.
 */
@Component
public class LoginAttemptLimiter {

    /**
     * Usernames are at most 50 characters (V1, and the register/profile validation), so
     * nothing longer can match an account. Truncating keeps a megabyte "username" from
     * becoming a megabyte key.
     */
    private static final int MAX_KEY_LENGTH = 50;

    private final boolean enabled;
    private final RateLimiter limiter;

    public LoginAttemptLimiter(
            @Value("${linkup.rate-limit.enabled:true}") boolean enabled,
            @Value("${linkup.rate-limit.login-per-username.requests:10}") int requests,
            @Value("${linkup.rate-limit.login-per-username.window-seconds:900}") long windowSeconds,
            @Value("${linkup.rate-limit.max-keys:100000}") int maxKeys) {
        this.enabled = enabled;
        this.limiter = new RateLimiter("login-per-username", requests, Duration.ofSeconds(windowSeconds), maxKeys);
    }

    /** @throws RateLimitExceededException if this username has used up its attempts */
    public void check(String username) {
        if (!enabled || username == null) {
            return;
        }
        String key = username.toLowerCase(Locale.ROOT);
        if (key.length() > MAX_KEY_LENGTH) {
            key = key.substring(0, MAX_KEY_LENGTH);
        }
        RateLimiter.Decision decision = limiter.tryAcquire(key);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }
}
