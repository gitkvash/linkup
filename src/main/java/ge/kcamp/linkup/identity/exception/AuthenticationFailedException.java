package ge.kcamp.linkup.identity.exception;

public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Incorrect username or password.");
    }
}
