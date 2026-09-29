package ge.kcamp.linkup.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when someone leaves a group or the owner removes them. Part of the
 * {@code social} module's event API. Nobody is notified: the group's other members and
 * the person who left just need their lists to stop showing what is gone.
 */
public record GroupMemberRemovedEvent(
        UUID groupId,
        UUID userId,
        Instant occurredAt
) {
}
