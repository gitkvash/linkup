package ge.kcamp.linkup.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after an account and everything it owned has been deleted
 * ({@link AccountDeletionService}). Part of the {@code identity} module's event API, for
 * the state that lives outside Postgres and so outside the delete: the feed drops the
 * account's Redis timeline, and notification closes its open live streams.
 */
public record AccountDeletedEvent(
        UUID userId,
        Instant occurredAt
) {
}
