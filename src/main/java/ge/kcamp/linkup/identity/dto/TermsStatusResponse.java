package ge.kcamp.linkup.identity.dto;

/**
 * Where the caller stands on the Terms and Privacy Policy. {@code acceptedVersion} is null
 * for an account that has never agreed; the client asks for agreement whenever it differs
 * from {@code currentVersion}.
 */
public record TermsStatusResponse(String currentVersion, String acceptedVersion) {
}
