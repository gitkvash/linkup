package ge.kcamp.linkup.identity.exception;

/**
 * An account was about to be created, or a record about to be made, without the person
 * having agreed to the current Terms and Privacy Policy. Maps to 428 {@code TERMS_REQUIRED}.
 */
public class TermsNotAcceptedException extends RuntimeException {

    public TermsNotAcceptedException() {
        super("Please agree to the Terms of Service and Privacy Policy to continue.");
    }
}
