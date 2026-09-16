package ge.kcamp.linkup.identity.exception;

public class GoogleAuthenticationFailedException extends RuntimeException {

    public GoogleAuthenticationFailedException() {
        super("Google sign-in failed. Please try again.");
    }
}
