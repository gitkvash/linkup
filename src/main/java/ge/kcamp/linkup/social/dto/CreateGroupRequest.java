package ge.kcamp.linkup.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code @Size} matches {@code groups.group_name VARCHAR(100)}. Without it a longer name
 * failed in the database and was reported as a 409 about a conflict that did not exist.
 */
public record CreateGroupRequest(
        @NotBlank @Size(max = 100) String groupName
) {
}
