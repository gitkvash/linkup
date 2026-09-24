package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Published alongside {@link ActivityDeletedEvent} when a host cancels a plan, for the
 * people who were in it.
 * <p>
 * A separate event rather than more fields on that one, whose shape the feed relies on.
 * And it has to carry everything: the plan's row and its participants are gone by the
 * time any listener runs ({@code ON DELETE CASCADE}), so the title and who to tell are
 * read before the delete and travel with the event.
 *
 * @param participantIds everyone joined or still invited, the host excluded
 */
public record ActivityCancelledEvent(
        UUID activityId,
        UUID hostId,
        String title,
        ZonedDateTime startTime,
        boolean hasTime,
        List<UUID> participantIds,
        Instant occurredAt
) {
}
