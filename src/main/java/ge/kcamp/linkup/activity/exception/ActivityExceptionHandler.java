package ge.kcamp.linkup.activity.exception;

import ge.kcamp.linkup.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Must outrank the global advice - see IdentityExceptionHandler. */
@RestControllerAdvice(basePackages = "ge.kcamp.linkup.activity.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActivityExceptionHandler {

    @ExceptionHandler(InvalidInviteeException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidInvitee(InvalidInviteeException ex) {
        return ApiError.of(
                HttpStatus.BAD_REQUEST, ex.getMessage(), ApiError.VALIDATION_FAILED,
                Map.of("inviteeUserIds", "must be accepted friends"));
    }

    @ExceptionHandler(InvalidGroupException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidGroup(InvalidGroupException ex) {
        return ApiError.of(
                HttpStatus.BAD_REQUEST, ex.getMessage(), ApiError.VALIDATION_FAILED,
                Map.of("groupId", ex.getMessage()));
    }

    /**
     * 404, matching {@code GET /activities/{id}} - a write must not reveal that an
     * activity exists when the read of the same activity wouldn't. Without this
     * mapping it fell through to the catch-all and surfaced as a 500.
     */
    @ExceptionHandler(ActivityNotVisibleException.class)
    public ResponseEntity<Map<String, Object>> handleNotVisible(ActivityNotVisibleException ex) {
        return ApiError.of(HttpStatus.NOT_FOUND, ex.getMessage(), ApiError.NOT_FOUND);
    }
}
