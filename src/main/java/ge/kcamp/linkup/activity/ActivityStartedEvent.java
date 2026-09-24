package ge.kcamp.linkup.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when a host starts their plan by hand - the first time only. Starting it
 * again is a no-op that keeps the first timestamp (see {@link ActivityLifecycleService}),
 * so a double tap doesn't tell everyone twice. A plan that starts by the clock publishes
 * nothing: its status is derived, and there is no moment at which it changes.
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
