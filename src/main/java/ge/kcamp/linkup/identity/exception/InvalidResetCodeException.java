package ge.kcamp.linkup.identity.exception;

/**
 * The one answer to every way a reset can fail: no such email, a Google-only account, no
 * code requested, wrong code, expired code, too many guesses. Telling them apart would
 * tell a stranger which addresses have accounts.
 */
public class InvalidResetCodeException extends RuntimeException {

    public InvalidResetCodeException() {
        super("That code is wrong or has expired. Request a new one and try again.");
    }
}
