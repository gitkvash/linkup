package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when a host starts their plan - the only way a plan goes live. A double tap
 * on a plan already running is a no-op that keeps the first timestamp (see
 * {@link ActivityLifecycleService}), so it doesn't tell everyone twice; each fresh run
 * does, which for a repeating plan means each occurrence the host starts.
 *
 * @param participantIds everyone joined or still invited, the host excluded - captured
 *                       here because the listener runs later, on another thread
 * @param occurredAt     the {@code startedAt} that was written
 */
public record ActivityStartedEvent(
        UUID activityId,
        UUID hostId,
        String title,
        List<UUID> participantIds,
        Instant occurredAt
) {
}
