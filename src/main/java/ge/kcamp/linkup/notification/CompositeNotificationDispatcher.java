package ge.kcamp.linkup.notification;

import ge.kcamp.linkup.notification.fcm.FcmNotificationDispatcher;
import ge.kcamp.linkup.notification.internal.LoggingNotificationDispatcher;
import ge.kcamp.linkup.notification.sse.SseNotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The active default dispatcher: persists+logs (so "list my notifications" stays real),
 * then pushes via FCM for background/offline delivery and via SSE for the live
 * foreground feed. Built from the concrete beans explicitly (not by autowiring
 * {@code List<NotificationDispatcher>} into itself, which would create a self-injection
 * cycle since this class also implements the interface).
 * <p>
 * The two kinds of step fail differently, on purpose:
 * <ul>
 *   <li><b>The row is written first, and its failure propagates.</b> The listener then
 *       fails, Modulith leaves the publication incomplete, and it is retried. Every
 *       failure used to be swallowed here, so a failed INSERT still "succeeded" and the
 *       notification was lost for good.</li>
 *   <li><b>A duplicate stops everything.</b> The dedupe key used to gate only the row,
 *       so every redelivery - on restart, or on a retry after one recipient of several
 *       failed - pushed to phones and open streams again.</li>
 *   <li><b>FCM and SSE are best-effort</b>: logged and skipped. The row is the durable
 *       record; failing the listener for a push that didn't go out would retry into a
 *       duplicate row, which then (correctly) sends nothing.</li>
 * </ul>
 * Meant to be called outside a transaction: the row commits in its own short one, and
 * the network sends then run holding no database connection.
 */
@Component
@Primary
public class CompositeNotificationDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CompositeNotificationDispatcher.class);

    private final LoggingNotificationDispatcher store;
    private final List<NotificationDispatcher> channels;

    public CompositeNotificationDispatcher(
            LoggingNotificationDispatcher loggingNotificationDispatcher,
            FcmNotificationDispatcher fcmNotificationDispatcher,
            SseNotificationDispatcher sseNotificationDispatcher) {
        this.store = loggingNotificationDispatcher;
        this.channels = List.of(fcmNotificationDispatcher, sseNotificationDispatcher);
    }

    /** No event identity: dedupes per type, recipient and subject, indefinitely. */
    @Override
    public void dispatch(NotificationMessage message) {
        dispatch(message, null);
    }

    /**
     * @param occurredAt the triggering event's timestamp, so a redelivery of that event
     *                   dedupes and a later event about the same subject does not
     */
    public void dispatch(NotificationMessage message, Instant occurredAt) {
        if (!store.record(message, occurredAt)) {
            return;
        }
        for (NotificationDispatcher channel : channels) {
            try {
                channel.dispatch(message);
            } catch (RuntimeException e) {
                log.warn("Notification channel {} failed for {}: {}",
                        channel.getClass().getSimpleName(), message.recipientUserId(), e.getMessage());
            }
        }
    }
}
