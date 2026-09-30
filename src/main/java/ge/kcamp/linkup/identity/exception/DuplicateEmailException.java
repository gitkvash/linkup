package ge.kcamp.linkup.identity.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("That email is already used by another account.");
    }
}
