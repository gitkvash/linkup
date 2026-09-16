package ge.kcamp.linkup.social;

import java.util.UUID;

public record GroupMemberSummary(UUID userId, String username, boolean owner) {
}
