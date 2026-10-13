package ge.kcamp.linkup.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The version of the documents the person was shown and agreed to. */
public record AcceptTermsRequest(@NotBlank @Size(max = 20) String version) {
}
