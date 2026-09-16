package ge.kcamp.linkup.identity.exception;

public class DuplicateUsernameException extends RuntimeException {

    /**
     * The message deliberately doesn't echo the username back. The caller already
     * knows what they typed, and not repeating it keeps the string safe to render
     * anywhere without escaping.
     */
    public DuplicateUsernameException() {
        super("That username is already taken.");
    }
}
