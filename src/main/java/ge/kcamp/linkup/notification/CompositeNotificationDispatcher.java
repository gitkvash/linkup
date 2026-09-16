package ge.kcamp.linkup.notification;

import ge.kcamp.linkup.notification.fcm.FcmNotificationDispatcher;
import ge.kcamp.linkup.notification.internal.LoggingNotificationDispatcher;
import ge.kcamp.linkup.notification.sse.SseNotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The active default dispatcher: persists+logs (so "list my notifications" stays real),
 * pushes via FCM for background/offline delivery, and pushes via SSE for the live
 * foreground feed. Built from the three concrete beans explicitly (not by autowiring
 * {@code List<NotificationDispatcher>} into itself, which would create a self-injection
 * cycle since this class also implements the interface).
 */
@Component
@Primary
public class CompositeNotificationDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CompositeNotificationDispatcher.class);

    private final List<NotificationDispatcher> dispatchers;

    public CompositeNotificationDispatcher(
            LoggingNotificationDispatcher loggingNotificationDispatcher,
            FcmNotificationDispatcher fcmNotificationDispatcher,
            SseNotificationDispatcher sseNotificationDispatcher) {
        this.dispatchers = List.of(loggingNotificationDispatcher, fcmNotificationDispatcher, sseNotificationDispatcher);
    }

    @Override
    public void dispatch(NotificationMessage message) {
        for (NotificationDispatcher dispatcher : dispatchers) {
            try {
                dispatcher.dispatch(message);
            } catch (Exception e) {
                log.warn("Notification dispatcher {} failed for {}: {}",
                        dispatcher.getClass().getSimpleName(), message.recipientUserId(), e.getMessage());
            }
        }
    }
}
