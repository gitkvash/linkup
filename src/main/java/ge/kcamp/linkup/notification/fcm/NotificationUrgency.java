package ge.kcamp.linkup.notification.fcm;

import ge.kcamp.linkup.notification.NotificationMessage;

import java.util.Map;
import java.util.Set;

/**
 * How loudly a push should arrive. {@link #LOUD} is for what someone has to act on or
 * will be caught out by - an invitation, a friend request, a plan that moved or was
 * called off, a reminder, a host asked whether their plan happened. {@link #QUIET} is for
 * news that can wait until they look: a friend made a plan, someone declined, a
 * suggestion was answered.
 * <p>
 * The client owns the actual sound: {@link #channelId} names an Android notification
 * channel that {@code MainActivity} creates, and a channel's importance can't be changed
 * by the sender afterwards, so these ids must match the ones in that file. An unknown
 * type is quiet: a new notification kind should have to ask to be loud.
 */
public enum NotificationUrgency {

    /** Sound and a heads-up banner. */
    LOUD("linkup_urgent"),

    /** A soft sound, no banner. */
    QUIET("linkup_updates");

    /**
     * Metadata key an {@code ACTIVITY_UPDATED} message carries, {@code "true"}, when the
     * edit moved the time or the place. A rename alone stays quiet.
     */
    public static final String URGENT_KEY = "urgent";

    private static final Set<String> LOUD_TYPES = Set.of(
            "ACTIVITY_INVITE",
            "ACTIVITY_CANCELLED",
            "FRIEND_REQUEST",
            "ACTIVITY_REMINDER",
            "ACTIVITY_STARTING",
            "ACTIVITY_STARTED",
            "ACTIVITY_CHECK_IN");

    private final String channelId;

    NotificationUrgency(String channelId) {
        this.channelId = channelId;
    }

    String channelId() {
        return channelId;
    }

    static NotificationUrgency of(NotificationMessage message) {
        String type = message.type();
        if (LOUD_TYPES.contains(type)) {
            return LOUD;
        }
        if ("ACTIVITY_UPDATED".equals(type)) {
            Map<String, String> metadata = message.metadata();
            return metadata != null && "true".equals(metadata.get(URGENT_KEY)) ? LOUD : QUIET;
        }
        return QUIET;
    }
}
