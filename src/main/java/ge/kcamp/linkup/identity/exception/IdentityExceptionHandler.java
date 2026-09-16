package ge.kcamp.linkup.identity.exception;

import ge.kcamp.linkup.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Explicit precedence matters: an unordered advice defaults to LOWEST_PRECEDENCE, the
 * same as {@code GlobalExceptionHandler}, and with equal order the winner is arbitrary.
 * That is how a wrong password started returning 500 - the global {@code Exception}
 * handler was consulted first and swallowed it.
 */
@RestControllerAdvice(basePackages = "ge.kcamp.linkup.identity.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdentityExceptionHandler {

    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateUsername(DuplicateUsernameException ex) {
        return ApiError.of(HttpStatus.CONFLICT, ex.getMessage(), ApiError.USERNAME_TAKEN);
    }

    /**
     * A refused login is a 401, same as an expired token - so the body carries
     * {@code INVALID_CREDENTIALS} to tell them apart. Without it the client showed
     * "Session expired. Please log in again." for a mistyped password, and its
     * interceptor cleared the session on the way past.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationFailed(AuthenticationFailedException ex) {
        return ApiError.of(HttpStatus.UNAUTHORIZED, ex.getMessage(), ApiError.INVALID_CREDENTIALS);
    }

    /**
     * Same status/code as a refused password login - the Flutter client only branches
     * on {@code INVALID_CREDENTIALS}, not on which auth method failed - but its own
     * message, since "Incorrect username or password." would be a non sequitur after a
     * Google sign-in attempt.
     */
    @ExceptionHandler(GoogleAuthenticationFailedException.class)
    public ResponseEntity<Map<String, Object>> handleGoogleAuthenticationFailed(
            GoogleAuthenticationFailedException ex) {
        return ApiError.of(HttpStatus.UNAUTHORIZED, ex.getMessage(), ApiError.INVALID_CREDENTIALS);
    }
}
