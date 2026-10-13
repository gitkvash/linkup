package ge.kcamp.linkup.activity.exception;

/**
 * The plan has reached its {@code maxParticipants} and the caller isn't already in it.
 * Mapped to 409: the request is fine, the plan's state is what refuses it - and it may
 * well accept the same request later, when someone leaves.
 */
public class ActivityFullException extends RuntimeException {

    public ActivityFullException() {
        super("This plan is full.");
    }
}
