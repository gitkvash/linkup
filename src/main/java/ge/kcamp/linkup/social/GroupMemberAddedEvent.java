package ge.kcamp.linkup.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the owner adds someone to a group. Part of the {@code social} module's
 * event API. The person added is told, the rest of the group sees the new name, and the
 * feed gives them the group's plans that are still on.
 *
 * @param addedBy the group's owner, who is the only one who can add
 */
public record GroupMemberAddedEvent(
        UUID groupId,
        UUID userId,
        UUID addedBy,
        Instant occurredAt
) {
}
