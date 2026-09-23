package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.notification.NotificationDispatcher;
import ge.kcamp.linkup.notification.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Persists an in-app row (and logs), so "list my notifications" is real.
 */
@Component
public class LoggingNotificationDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationDispatcher.class);

    /** Metadata keys that identify what a notification is about, most specific first. */
    private static final String[] SUBJECT_KEYS = {"activityId", "otherUserId", "groupId"};

    private final NotificationRepository notificationRepository;

    public LoggingNotificationDispatcher(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * REQUIRES_NEW, deliberately. This runs from a Modulith module listener, which is
     * itself transactional - but it used to run inside an AFTER_COMMIT callback with
     * plain REQUIRED propagation, joining a transaction that had already committed. The
     * INSERT went to a connection that would never commit again: the log line said the
     * notification was sent, and no row ever appeared.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(NotificationMessage message) {
        String dedupeKey = dedupeKeyFor(message);

        // Now that outstanding publications are retried on restart, the same event can
        // arrive twice. Checking first (rather than relying on the unique index) keeps
        // the transaction clean; if two deliveries race, the index rejects one, the
        // listener fails, and the retry finds the row already there.
        if (dedupeKey != null && notificationRepository.existsByDedupeKey(dedupeKey)) {
            log.debug("Skipping duplicate notification {}", dedupeKey);
            return;
        }

        log.info("Notification for {}: [{}] {}", message.recipientUserId(), message.type(), message.title());

        Notification notification = new Notification();
        notification.setRecipientUserId(message.recipientUserId());
        notification.setType(message.type());
        notification.setTitle(message.title());
        notification.setBody(message.body());
        notification.setDedupeKey(dedupeKey);
        notification.setActivityId(activityIdOf(message));
        notificationRepository.save(notification);
    }

    /** The {@code activityId} metadata entry, if there is one and it is a UUID. */
    private static UUID activityIdOf(NotificationMessage message) {
        Map<String, String> metadata = message.metadata();
        String raw = metadata == null ? null : metadata.get("activityId");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code type:recipient:subject}, or null when the message names no subject - in
     * which case two otherwise-identical notifications are assumed to be genuinely
     * distinct rather than collapsed into one.
     */
    private static String dedupeKeyFor(NotificationMessage message) {
        Map<String, String> metadata = message.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : SUBJECT_KEYS) {
            String subject = metadata.get(key);
            if (subject != null && !subject.isBlank()) {
                return message.type() + ':' + message.recipientUserId() + ':' + subject;
            }
        }
        return null;
    }
}
