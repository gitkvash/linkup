package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.notification.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists the in-app row (and logs it), so "list my notifications" is real - and decides,
 * through that row, whether the notification is new at all.
 * <p>
 * No longer a {@code NotificationDispatcher} in its own right. It is the first step of
 * {@code CompositeNotificationDispatcher}, which only sends a push or SSE event for a
 * notification this class has just inserted.
 */
@Component
public class LoggingNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationDispatcher.class);

    /** Metadata keys that identify what a notification is about, most specific first. */
    private static final String[] SUBJECT_KEYS = {"activityId", "otherUserId", "groupId"};

    /**
     * One statement that both inserts and dedupes. The old check-then-insert raced (two
     * deliveries could both see no row), and the loser's unique-index violation was
     * swallowed along with every other dispatcher failure. {@code uq_notifications_dedupe_key}
     * (V10) is a plain unique index, so it can arbitrate; NULL keys never conflict.
     */
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO notifications
                (notification_id, recipient_user_id, type, title, body, dedupe_key, activity_id)
            VALUES (:id, :recipientUserId, :type, :title, :body, :dedupeKey, :activityId)
            ON CONFLICT (dedupe_key) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public LoggingNotificationDispatcher(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Writes the row unless one with the same dedupe key exists.
     * <p>
     * Exceptions propagate, deliberately. Every dispatcher failure used to be caught and
     * logged, so the listener always returned normally, Modulith marked the publication
     * complete, and a failed INSERT was never retried - the notification was just gone.
     * A failure here now fails the listener and leaves the publication incomplete.
     * <p>
     * {@code @Transactional} is what keeps the connection short-lived, not a formality.
     * The caller runs outside any transaction (see {@code ActivityEventListener}), where a
     * bare JDBC call would bind its connection to the listener's synchronization scope
     * and hold it through the FCM and SSE sends that follow. A real transaction here
     * returns the connection the moment the row commits. It also replaces the
     * REQUIRES_NEW this method used to have, which needed a second connection from the
     * small system pool while the listener's own transaction held the first.
     *
     * @param occurredAt the triggering event's timestamp, which makes the dedupe key
     *                   identify one <em>event</em> - see {@link #dedupeKeyFor}. Null
     *                   falls back to the per-subject key.
     * @return true if a row was written; false if this notification already existed
     */
    @Transactional
    public boolean record(NotificationMessage message, Instant occurredAt) {
        if (jdbcTemplate.update(INSERT_IF_ABSENT, paramsFor(message, occurredAt)) == 0) {
            log.debug("Skipping duplicate notification {}", dedupeKeyFor(message, occurredAt));
            return false;
        }
        log.info("Notification for {}: [{}] {}", message.recipientUserId(), message.type(), message.title());
        return true;
    }

    /**
     * {@link #record} for many messages, as one JDBC batch in one transaction. Same
     * failure rule: an exception propagates, and none of the rows are kept.
     *
     * @return the messages whose row was written, in order; duplicates are left out
     */
    @Transactional
    public List<NotificationMessage> recordAll(List<NotificationMessage> messages, Instant occurredAt) {
        if (messages.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource[] batch = messages.stream()
                .map(message -> paramsFor(message, occurredAt))
                .toArray(MapSqlParameterSource[]::new);
        int[] counts = jdbcTemplate.batchUpdate(INSERT_IF_ABSENT, batch);

        List<NotificationMessage> written = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            // 0 is a dedupe. Anything else counts as written - including the driver's
            // "succeeded, count unknown", where a rare second push beats a lost one.
            if (i >= counts.length || counts[i] != 0) {
                NotificationMessage message = messages.get(i);
                log.info("Notification for {}: [{}] {}", message.recipientUserId(), message.type(), message.title());
                written.add(message);
            }
        }
        if (written.size() < messages.size()) {
            log.debug("Skipped {} duplicate notification(s)", messages.size() - written.size());
        }
        return written;
    }

    private static MapSqlParameterSource paramsFor(NotificationMessage message, Instant occurredAt) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID(), Types.OTHER)
                .addValue("recipientUserId", message.recipientUserId(), Types.OTHER)
                .addValue("type", message.type(), Types.VARCHAR)
                .addValue("title", message.title(), Types.VARCHAR)
                .addValue("body", message.body(), Types.VARCHAR)
                .addValue("dedupeKey", dedupeKeyFor(message, occurredAt), Types.VARCHAR)
                .addValue("activityId", activityIdOf(message), Types.OTHER);
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
     * {@code type:recipient:subject:occurredAtMillis}, or null when the message names no
     * subject - in which case two otherwise-identical notifications are assumed to be
     * genuinely distinct rather than collapsed into one.
     * <p>
     * The event's timestamp is part of the key so that it identifies one event, not one
     * subject forever. As {@code type:recipient:subject} it never expired: after a
     * declined request was sent again, or an unfriended pair became friends again, the
     * new FRIEND_REQUEST / FRIEND_ACCEPTED matched the first one's key and was silently
     * suppressed for good. A redelivery of the same event carries the same timestamp,
     * so it still dedupes. Milliseconds rather than the full instant, so a serializer
     * that drops sub-millisecond precision on the round trip through
     * {@code event_publication} can't make a redelivery look new. Fits V10's
     * {@code VARCHAR(200)} with room to spare.
     */
    static String dedupeKeyFor(NotificationMessage message, Instant occurredAt) {
        Map<String, String> metadata = message.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : SUBJECT_KEYS) {
            String subject = metadata.get(key);
            if (subject != null && !subject.isBlank()) {
                String base = message.type() + ':' + message.recipientUserId() + ':' + subject;
                return occurredAt == null ? base : base + ':' + occurredAt.toEpochMilli();
            }
        }
        return null;
    }
}
