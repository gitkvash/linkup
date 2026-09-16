package ge.kcamp.linkup.activity.exception;

/**
 * The activity doesn't exist, or the caller may not see it - deliberately the same
 * exception for both, so a 404 can't be used to probe which ids exist.
 */
public class ActivityNotVisibleException extends RuntimeException {

    public ActivityNotVisibleException() {
        super("That plan doesn't exist, or you don't have access to it.");
    }
}
