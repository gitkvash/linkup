package ge.kcamp.linkup;

import ge.kcamp.linkup.nlp.NlpUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catch-all advice so no endpoint can answer with an opaque body. Before this,
 * only {@code identity} and {@code social} had (package-scoped) advices, so a
 * validation failure on {@code POST /activities} returned
 * {@code {timestamp,status,error,path}} with the field errors computed and then
 * discarded, an out-of-range bounding box surfaced as a 500 instead of a 400,
 * and a duplicate-username race surfaced as a 500 instead of a 409.
 * <p>
 * Lowest precedence, so the module-specific advices keep priority for their own
 * exception types.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean validation on a {@code @Valid @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), defaultIfBlank(error.getDefaultMessage(), "is invalid")));
        ex.getBindingResult().getGlobalErrors().forEach(error ->
                fields.putIfAbsent(error.getObjectName(), defaultIfBlank(error.getDefaultMessage(), "is invalid")));

        String message = fields.isEmpty()
                ? "Some of the values sent are not valid."
                : fields.entrySet().stream()
                        .map(entry -> entry.getKey() + " " + entry.getValue())
                        .reduce((a, b) -> a + "; " + b)
                        .orElseThrow();

        return ApiError.of(HttpStatus.BAD_REQUEST, message, ApiError.VALIDATION_FAILED, fields);
    }

    /**
     * Unparseable body: malformed JSON, a value of the wrong type, or - the common
     * one - an enum constant the server doesn't know. Jackson's own message names
     * internal types, so it isn't forwarded verbatim.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Rejected unreadable request body", ex);
        return ApiError.of(
                HttpStatus.BAD_REQUEST,
                "The request body couldn't be read. Check that every field has the expected type.",
                ApiError.MALFORMED_REQUEST);
    }

    /**
     * Constraint violations from the AOP method-validation path. Spring 6.1+ normally
     * raises {@code HandlerMethodValidationException} instead (handled below via
     * {@link ErrorResponseException}); this covers any {@code @Validated} bean.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            int lastDot = path.lastIndexOf('.');
            fields.putIfAbsent(lastDot >= 0 ? path.substring(lastDot + 1) : path, violation.getMessage());
        });
        return ApiError.of(
                HttpStatus.BAD_REQUEST,
                "Some of the values sent are not valid.",
                ApiError.VALIDATION_FAILED,
                fields);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ApiError.of(
                HttpStatus.BAD_REQUEST,
                "Missing required parameter '" + ex.getParameterName() + "'.",
                ApiError.MISSING_PARAMETER,
                Map.of(ex.getParameterName(), "is required"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleParameterTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ApiError.of(
                HttpStatus.BAD_REQUEST,
                "Parameter '" + ex.getName() + "' has an unexpected value.",
                ApiError.INVALID_PARAMETER,
                Map.of(ex.getName(), "is not a valid value"));
    }

    /**
     * Hand-rolled argument checks (e.g. {@code BoundingBox.validate()}) throw this.
     * They were reaching the client as a 500 with no explanation.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiError.of(
                HttpStatus.BAD_REQUEST,
                defaultIfBlank(ex.getMessage(), "The request was not valid."),
                ApiError.VALIDATION_FAILED);
    }

    /**
     * Unique/foreign-key violations. The specific messages are deliberately vague
     * about which constraint fired - the constraint name is an internal detail -
     * but the status is now correct (409, not 500).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        return ApiError.of(
                HttpStatus.CONFLICT,
                "That conflicts with something that already exists, or refers to something that doesn't.",
                ApiError.CONFLICT);
    }

    /**
     * Text parsing capacity is exhausted. 503 + Retry-After, so this reads as "try
     * again" rather than "the server is broken".
     */
    @ExceptionHandler(NlpUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleNlpUnavailable(NlpUnavailableException ex) {
        return ApiError.of(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), "NLP_BUSY");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return ApiError.of(HttpStatus.FORBIDDEN, "You don't have access to that.", ApiError.ACCESS_DENIED);
    }

    /**
     * Spring MVC's own exceptions (no handler, method not allowed, unsupported media
     * type, ...) already carry a status; keep it and just reshape the body.
     * <p>
     * They have to be listed by name. They all implement {@link ErrorResponse}, but only
     * some of them <em>extend</em> {@link ErrorResponseException} - notably
     * {@code ResponseStatusException} and its subclasses do, while
     * {@link HttpRequestMethodNotSupportedException},
     * {@link HttpMediaTypeNotSupportedException} and {@link NoResourceFoundException} do
     * not. Handling the exception type alone therefore missed exactly the everyday
     * client mistakes: a wrong method, a wrong content type and an unknown path all fell
     * through to the catch-all below and came back 500, with a stack trace logged at
     * ERROR for each. The client reads 5xx as "the server is broken" and 404 as "not
     * found", so a mistyped URL told the user to try again later, forever.
     * <p>
     * {@code @ExceptionHandler} cannot bind an interface, hence the explicit list; the
     * cast is safe because every type in it implements {@link ErrorResponse}.
     */
    @ExceptionHandler({
            ErrorResponseException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            NoResourceFoundException.class,
            NoHandlerFoundException.class,
            ServletRequestBindingException.class,
            AsyncRequestTimeoutException.class,
    })
    public ResponseEntity<Map<String, Object>> handleErrorResponse(Exception ex) {
        ErrorResponse errorResponse = (ErrorResponse) ex;
        HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        log.debug("Rejected request: {}", ex.getMessage());
        String detail = errorResponse.getBody().getDetail();
        return ApiError.of(
                status,
                defaultIfBlank(detail, status.getReasonPhrase()),
                status == HttpStatus.NOT_FOUND ? ApiError.NOT_FOUND : null);
    }

    /**
     * Anything unmapped. Logged at error with the stack trace; the response says
     * nothing about internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Please try again.",
                ApiError.INTERNAL_ERROR);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
