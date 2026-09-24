package ge.kcamp.linkup.social.exception;

import ge.kcamp.linkup.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Must outrank the global advice - see IdentityExceptionHandler. */
@RestControllerAdvice(basePackages = "ge.kcamp.linkup.social.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SocialExceptionHandler {

    @ExceptionHandler(SelfFriendRequestException.class)
    public ResponseEntity<Map<String, Object>> handleSelfRequest(SelfFriendRequestException ex) {
        return ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage(), ApiError.VALIDATION_FAILED);
    }

    @ExceptionHandler(FriendshipBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleBlocked(FriendshipBlockedException ex) {
        return ApiError.of(HttpStatus.FORBIDDEN, ex.getMessage(), ApiError.ACCESS_DENIED);
    }

    @ExceptionHandler(FriendRequestNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(FriendRequestNotFoundException ex) {
        return ApiError.of(HttpStatus.NOT_FOUND, ex.getMessage(), ApiError.NOT_FOUND);
    }

    /** 400 with the reason, which the client shows as-is. */
    @ExceptionHandler(GroupMemberNotFriendException.class)
    public ResponseEntity<Map<String, Object>> handleNotFriend(GroupMemberNotFriendException ex) {
        return ApiError.of(HttpStatus.BAD_REQUEST, ex.getMessage(), ApiError.VALIDATION_FAILED);
    }

    @ExceptionHandler(GroupNotOwnedException.class)
    public ResponseEntity<Map<String, Object>> handleNotOwned(GroupNotOwnedException ex) {
        return ApiError.of(HttpStatus.FORBIDDEN, ex.getMessage(), ApiError.ACCESS_DENIED);
    }
}
