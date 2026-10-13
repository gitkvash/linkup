package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.exception.TermsNotAcceptedException;

/**
 * Which edition of the Terms of Service and Privacy Policy is in force. One value for both
 * documents: a person agrees to the pair, and a change to either is a new version.
 * <p>
 * The Flutter client carries the same string ({@code LegalDocuments.version} in
 * {@code lib/core/legal/legal_documents.dart}) and sends the one it showed. Accepting is
 * therefore always accepting what was on screen: a client that is behind is refused rather
 * than recorded as having agreed to text it never displayed. Bump both together whenever
 * {@code legal/terms.html} or {@code legal/privacy.html} changes in substance.
 */
public final class LegalDocuments {

    private LegalDocuments() {
    }

    /** The date the current text took effect, which is also what the pages print. */
    public static final String CURRENT_VERSION = "2026-10-13";

    /** Stated in both documents and confirmed by the sign-up checkbox. */
    public static final int MINIMUM_AGE = 16;

    /**
     * @throws TermsNotAcceptedException unless {@code version} is exactly the current one
     */
    public static void requireCurrent(String version) {
        if (!CURRENT_VERSION.equals(version)) {
            throw new TermsNotAcceptedException();
        }
    }
}
