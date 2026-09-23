package ge.kcamp.linkup.identity.exception;

/**
 * A refresh token that is expired, malformed, of the wrong kind, or belongs to an
 * account that no longer exists. The caller can't tell those apart, deliberately - the
 * answer to every one of them is "sign in again".
 */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException() {
        super("Your session has ended. Please sign in again.");
    }
}
