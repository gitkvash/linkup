package ge.kcamp.linkup;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single error body shape every endpoint returns. Lives in the root package
 * (alongside the application class) rather than in a module, so all modules can
 * use it without introducing module dependencies.
 * <p>
 * {@code message} is the contract the Flutter client reads to show the user
 * something useful - Spring Boot's default error body deliberately strips it
 * ({@code server.error.include-message} defaults to {@code never}, and
 * {@code ErrorAttributeOptions.retainIncluded} removes the key outright), which
 * is why every validation failure used to reach the user as "Invalid request."
 * <p>
 * {@code code} is the machine-readable discriminator for the few cases the
 * client branches on; {@code fields} carries per-field validation detail.
 * Null members are omitted rather than serialized as {@code null}.
 */
public final class ApiError {

    private ApiError() {
    }

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String MISSING_PARAMETER = "MISSING_PARAMETER";
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    public static final String CONFLICT = "CONFLICT";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String SESSION_EXPIRED = "SESSION_EXPIRED";

    public static ResponseEntity<Map<String, Object>> of(HttpStatus status, String message) {
        return of(status, message, null, null);
    }

    public static ResponseEntity<Map<String, Object>> of(HttpStatus status, String message, String code) {
        return of(status, message, code, null);
    }

    public static ResponseEntity<Map<String, Object>> of(
            HttpStatus status, String message, String code, Map<String, String> fields) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (code != null) {
            body.put("code", code);
        }
        if (fields != null && !fields.isEmpty()) {
            body.put("fields", fields);
        }
        return ResponseEntity.status(status).body(body);
    }
}
