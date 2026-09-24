package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.activity.ActivityInvitationsSentEvent;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.notification.CompositeNotificationDispatcher;
import ge.kcamp.linkup.notification.NotificationMessage;
import ge.kcamp.linkup.social.FriendRequestReceivedEvent;
import ge.kcamp.linkup.social.FriendshipAcceptedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Observer-pattern payoff: neither {@code activity} nor {@code social} knows this class
 * exists.
 * <p>
 * {@code @ApplicationModuleListener} rather than a bare
 * {@code @TransactionalEventListener(AFTER_COMMIT)}: it adds {@code @Async}, plus
 * Modulith's publication log for retries. That matters for correctness, not just
 * delivery guarantees - running the dispatcher inline in the after-commit callback made
 * it join a transaction that had already committed, so the {@code notifications} INSERT
 * was written to a connection that would never commit again. The row was logged as sent
 * and then silently lost.
 * <p>
 * {@code propagation = NOT_SUPPORTED} rather than the default {@code REQUIRES_NEW}: no
 * transaction around the listener, so no connection held for its whole run. Every
 * listener on the executor used to pin one connection from the small system pool for
 * the entire method, including the FCM {@code sendEach} round trip and the SSE writes -
 * and the old dispatcher then asked for a second connection for its own REQUIRES_NEW
 * insert while holding the first, so a busy executor could exhaust the pool against
 * itself. Now each step takes a connection only for as long as its own short
 * transaction: the directory lookup, the notification row
 * ({@link LoggingNotificationDispatcher#record}), and the FCM token read and prune. The
 * network sends run between them holding none. The {@code @Async} executor still stamps
 * the thread {@code SYSTEM}; the propagation doesn't change which pool is used.
 * <p>
 * Failure semantics are {@link CompositeNotificationDispatcher}'s: if a row can't be
 * written this listener throws, so the publication stays incomplete and is retried, and
 * rows already written for other recipients dedupe on the retry without being pushed a
 * second time.
 */
@Component
public class ActivityEventListener {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

    private final CompositeNotificationDispatcher notificationDispatcher;
    private final UserDirectoryService userDirectoryService;
    private final ZoneId zone;

    /**
     * @param zone the zone push text renders plan times in. The server formats them, and
     *             it used to use the JVM's zone - UTC on Render - which put every time
     *             four hours off for Tbilisi. The client doesn't send a zone with the
     *             events these come from, so it is one deployment-wide setting.
     */
    public ActivityEventListener(
            CompositeNotificationDispatcher notificationDispatcher,
            UserDirectoryService userDirectoryService,
            @Value("${linkup.notification.zone:Asia/Tbilisi}") ZoneId zone) {
        this.notificationDispatcher = notificationDispatcher;
        this.userDirectoryService = userDirectoryService;
        this.zone = zone;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onActivityCreated(ActivityCreatedEvent event) {
        notifyInvitees(event.activityId(), event.creatorId(), event.title(),
                event.startTime(), event.invitedUserIds(), event.occurredAt());
    }

    /** More people invited to a plan that already existed - the same message they'd get at creation. */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onInvitationsSent(ActivityInvitationsSentEvent event) {
        notifyInvitees(event.activityId(), event.inviterId(), event.title(),
                event.startTime(), event.invitedUserIds(), event.occurredAt());
    }

    /**
     * Names the host in the title: "Nino invited you" is a reason to open it, where
     * "You're invited" from nobody in particular is not. The dedupe key is
     * type:recipient:activity:event-time, so an invitation redelivered after a restart
     * is still one row.
     */
    private void notifyInvitees(
            UUID activityId, UUID hostId, String title, ZonedDateTime startTime,
            List<UUID> invitees, Instant occurredAt) {
        if (invitees.isEmpty()) {
            return;
        }
        String host = userDirectoryService.findById(hostId)
                .map(UserSummary::username)
                .orElse("Someone");
        for (var inviteeId : invitees) {
            if (inviteeId.equals(hostId)) {
                continue;
            }
            notificationDispatcher.dispatch(new NotificationMessage(
                    inviteeId,
                    "ACTIVITY_INVITE",
                    host + " invited you: " + title,
                    "Starts " + formatWhen(startTime),
                    Map.of("activityId", activityId.toString(), "otherUserId", hostId.toString())
            ), occurredAt);
        }
    }

    /** In {@link #zone}, whatever offset the stored time happens to carry. */
    String formatWhen(ZonedDateTime startTime) {
        return WHEN.format(startTime.withZoneSameInstant(zone));
    }

    /**
     * Someone asked to be friends. Carries {@code otherUserId} so tapping the
     * notification can open the request.
     */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onFriendRequestReceived(FriendRequestReceivedEvent event) {
        String requester = userDirectoryService.findById(event.requesterId())
                .map(UserSummary::username)
                .orElse("Someone");

        notificationDispatcher.dispatch(new NotificationMessage(
                event.recipientId(),
                "FRIEND_REQUEST",
                requester + " wants to be friends",
                "Accept or decline from the People tab.",
                Map.of("otherUserId", event.requesterId().toString())), event.occurredAt());
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onFriendshipAccepted(FriendshipAcceptedEvent event) {
        // A real body, not null. The client's model declared it non-nullable, so the
        // null this used to send made the whole notifications list fail to parse - and
        // because a decode error isn't a transport error, it surfaced as a bare
        // "Something went wrong." that never went away.
        notificationDispatcher.dispatch(new NotificationMessage(
                event.userAId(), "FRIEND_ACCEPTED", "You have a new friend",
                "You're now connected. Say hello!",
                Map.of("otherUserId", event.userBId().toString())), event.occurredAt());
        notificationDispatcher.dispatch(new NotificationMessage(
                event.userBId(), "FRIEND_ACCEPTED", "You have a new friend",
                "You're now connected. Say hello!",
                Map.of("otherUserId", event.userAId().toString())), event.occurredAt());
    }
}
