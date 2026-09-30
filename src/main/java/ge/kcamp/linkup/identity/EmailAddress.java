package ge.kcamp.linkup.identity;

import java.util.Locale;

/**
 * The one spelling an email is stored and looked up in: trimmed, lowercase (V37's CHECK
 * refuses anything else). Case differs between how people type an address and how it was
 * typed at signup, and a reset that missed on a capital letter would look like "no such
 * account".
 */
final class EmailAddress {

    private EmailAddress() {
    }

    /** Null for null or blank, so an empty form field means "no email" rather than "". */
    static String normalize(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
