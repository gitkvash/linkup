package ge.kcamp.linkup.identity.dto;

/** The caller's own reset address, or null if they never gave one. Private to its owner. */
public record EmailResponse(String email) {
}
