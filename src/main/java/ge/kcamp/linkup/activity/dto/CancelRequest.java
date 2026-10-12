package ge.kcamp.linkup.activity.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body for {@code POST /activities/{id}/cancel}: why the host is calling the plan
 * off, told to everyone in it. In the body rather than the query string so free text stays
 * out of access logs.
 */
public record CancelRequest(@Size(max = MAX_REASON) String reason) {

    /** The same limit as a decline reason and a time suggestion's message. */
    public static final int MAX_REASON = 200;
}
