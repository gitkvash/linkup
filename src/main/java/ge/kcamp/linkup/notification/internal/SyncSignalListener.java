package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.activity.ActivityChangedEvent;
import ge.kcamp.linkup.activity.ActivityInvitationsSentEvent;
import ge.kcamp.linkup.activity.ActivityParticipationService;
import ge.kcamp.linkup.activity.ActivityUpdatedEvent;
import ge.kcamp.linkup.feed.FeedTimelinesUpdatedEvent;
import ge.kcamp.linkup.notification.sse.SseEmitterRegistry;
import ge.kcamp.linkup.notification.sse.SyncSignal;
import ge.kcamp.linkup.social.FriendRequestDeclinedEvent;
import ge.kcamp.linkup.social.FriendshipEndedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Turns changes that have no notification of their own into silent {@code sync} signals
 * on the open SSE streams.
 * <p>
 * Every list in the app is fetched once and then held, so a change made by someone else
 * stayed invisible until the owner restarted the app or pulled to refresh. The changes
 * that <em>do</em> notify (an invitation, a friend request, an accepted friendship, a
 * cancelled plan) reach the client as notifications and are handled by type there; this
 * covers the rest. Nothing is stored and nobody is pushed to: a user with no open stream
 * simply re-reads on their next launch.
 */
@Component
class SyncSignalListener {

    private final SseEmitterRegistry registry;
    private final ActivityParticipationService participationService;

    SyncSignalListener(SseEmitterRegistry registry, ActivityParticipationService participationService) {
        this.registry = registry;
        this.participationService = participationService;
    }

    /** After the Redis write, so the client's re-read finds the new plan. */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void onFeedTimelinesUpdated(FeedTimelinesUpdatedEvent event) {
        signal(event.userIds(), new SyncSignal(List.of(SyncSignal.FEED, SyncSignal.MAP), null));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void onActivityChanged(ActivityChangedEvent event) {
        signalPlan(event.activityId());
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void onActivityUpdated(ActivityUpdatedEvent event) {
        signalPlan(event.activityId());
    }

    /** The people already in the plan see the new names appear. The invitees are notified separately. */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void onInvitationsSent(ActivityInvitationsSentEvent event) {
        signalPlan(event.activityId());
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void onFriendshipEnded(FriendshipEndedEvent event) {
        signal(List.of(event.userAId(), event.userBId()), new SyncSignal(
                List.of(SyncSignal.FRIENDS, SyncSignal.REQUESTS, SyncSignal.FEED, SyncSignal.MAP), null));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void onFriendRequestDeclined(FriendRequestDeclinedEvent event) {
        signal(List.of(event.requesterId()), new SyncSignal(List.of(SyncSignal.REQUESTS), null));
    }

    private void signalPlan(UUID activityId) {
        signal(participationService.audienceOf(activityId), new SyncSignal(
                List.of(SyncSignal.PLANS, SyncSignal.FEED, SyncSignal.MAP), activityId.toString()));
    }

    private void signal(Collection<UUID> userIds, SyncSignal signal) {
        userIds.forEach(userId -> registry.pushSync(userId, signal));
    }
}
