package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.activity.ActivityInvitationsSentEvent;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.notification.NotificationDispatcher;
import ge.kcamp.linkup.notification.NotificationMessage;
import ge.kcamp.linkup.social.FriendRequestReceivedEvent;
import ge.kcamp.linkup.social.FriendshipAcceptedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Observer-pattern payoff: neither {@code activity} nor {@code social} knows this class
 * exists.
 * <p>
 * {@code @ApplicationModuleListener} rather than a bare
 * {@code @TransactionalEventListener(AFTER_COMMIT)}: it adds {@code @Async} and
 * {@code REQUIRES_NEW}, plus Modulith's publication log for retries. That matters for
 * correctness, not just delivery guarantees - the dispatcher is {@code @Transactional},
 * and running it inline in the after-commit callback made it join a transaction that had
 * already committed, so the {@code notifications} INSERT was written to a connection that
 * would never commit again. The row was logged as sent and then silently lost.
 */
@Component
public class ActivityEventListener {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

    private final NotificationDispatcher notificationDispatcher;
    private final UserDirectoryService userDirectoryService;

    public ActivityEventListener(
            NotificationDispatcher notificationDispatcher,
            UserDirectoryService userDirectoryService) {
        this.notificationDispatcher = notificationDispatcher;
        this.userDirectoryService = userDirectoryService;
    }

    @ApplicationModuleListener
    public void onActivityCreated(ActivityCreatedEvent event) {
        notifyInvitees(event.activityId(), event.creatorId(), event.title(),
                event.startTime(), event.invitedUserIds());
    }

    /** More people invited to a plan that already existed - the same message they'd get at creation. */
    @ApplicationModuleListener
    public void onInvitationsSent(ActivityInvitationsSentEvent event) {
        notifyInvitees(event.activityId(), event.inviterId(), event.title(),
                event.startTime(), event.invitedUserIds());
    }

    /**
     * Names the host in the title: "Nino invited you" is a reason to open it, where
     * "You're invited" from nobody in particular is not. The dedupe key is
     * type:recipient:activity, so an invitation redelivered after a restart is still one row.
     */
    private void notifyInvitees(
            UUID activityId, UUID hostId, String title, ZonedDateTime startTime, List<UUID> invitees) {
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
                    "Starts " + WHEN.format(startTime),
                    Map.of("activityId", activityId.toString(), "otherUserId", hostId.toString())
            ));
        }
    }

    /**
     * Someone asked to be friends. Carries {@code otherUserId} so tapping the
     * notification can open the request.
     */
    @ApplicationModuleListener
    public void onFriendRequestReceived(FriendRequestReceivedEvent event) {
        String requester = userDirectoryService.findById(event.requesterId())
                .map(UserSummary::username)
                .orElse("Someone");

        notificationDispatcher.dispatch(new NotificationMessage(
                event.recipientId(),
                "FRIEND_REQUEST",
                requester + " wants to be friends",
                "Accept or decline from the People tab.",
                Map.of("otherUserId", event.requesterId().toString())));
    }

    @ApplicationModuleListener
    public void onFriendshipAccepted(FriendshipAcceptedEvent event) {
        // A real body, not null. The client's model declared it non-nullable, so the
        // null this used to send made the whole notifications list fail to parse - and
        // because a decode error isn't a transport error, it surfaced as a bare
        // "Something went wrong." that never went away.
        notificationDispatcher.dispatch(new NotificationMessage(
                event.userAId(), "FRIEND_ACCEPTED", "You have a new friend",
                "You're now connected. Say hello!",
                Map.of("otherUserId", event.userBId().toString())));
        notificationDispatcher.dispatch(new NotificationMessage(
                event.userBId(), "FRIEND_ACCEPTED", "You have a new friend",
                "You're now connected. Say hello!",
                Map.of("otherUserId", event.userAId().toString())));
    }
}
