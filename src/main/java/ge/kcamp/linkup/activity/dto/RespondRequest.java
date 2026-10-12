package ge.kcamp.linkup.activity.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body for {@code POST /activities/{id}/respond}: why the caller is declining.
 * It travels in the body rather than the query string so free text doesn't end up in
 * access logs. Ignored when accepting.
 */
public record RespondRequest(@Size(max = MAX_REASON) String reason) {

    /** About 30-40 words; the same limit as a time suggestion's message. */
    public static final int MAX_REASON = 200;
}
