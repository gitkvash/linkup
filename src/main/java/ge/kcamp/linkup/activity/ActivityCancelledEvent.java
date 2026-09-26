package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The host called a plan off, for the people who were in it. Published two ways: by
 * {@link ActivityLifecycleService#cancel}, which keeps the plan (it reads as cancelled),
 * and alongside {@link ActivityDeletedEvent} when the host deletes it outright.
 * <p>
 * A separate event rather than more fields on the deleted one, whose shape the feed
 * relies on. And it has to carry everything: after a delete the plan's row and its
 * participants are gone by the time any listener runs ({@code ON DELETE CASCADE}), so
 * the title and who to tell are read first and travel with the event.
 * <p>
 * A plan the clock cancels - nobody started it in time - publishes nothing: its status
 * is derived, and there is no moment at which it changes.
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
