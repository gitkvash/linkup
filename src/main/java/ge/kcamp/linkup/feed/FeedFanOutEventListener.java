package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.identity.AccountDeletedEvent;
import ge.kcamp.linkup.social.FriendshipAcceptedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Independent listener on the same event {@code notification}'s ActivityEventListener
 * reacts to. {@code @ApplicationModuleListener} (rather than a plain
 * {@code @TransactionalEventListener}) gives at-least-once delivery via Modulith's event
 * publication log, which matters here since fan-out iterates over potentially many
 * friends.
 */
@Component
class FeedFanOutEventListener {

    private final FeedFanOutService feedFanOutService;
    private final RedisFeedTimelineRepository timelineRepository;

    FeedFanOutEventListener(FeedFanOutService feedFanOutService, RedisFeedTimelineRepository timelineRepository) {
        this.feedFanOutService = feedFanOutService;
        this.timelineRepository = timelineRepository;
    }

    @ApplicationModuleListener
    void onActivityCreated(ActivityCreatedEvent event) {
        // Ranked by when the activity happens, not when it was created: the feed is
        // ordered and paginated by start time, and scoring by creation instant meant
        // every cursor the client sent back pointed above the entire timeline.
        feedFanOutService.fanOutOnWrite(
                event.creatorId(), event.activityId(), event.startTime().toInstant(), event.groupId());
    }

    /**
     * Safe to redeliver: a timeline is a sorted set keyed by activity, so pushing the
     * same plan twice leaves one entry with the same score.
     */
    @ApplicationModuleListener
    void onFriendshipAccepted(FriendshipAcceptedEvent event) {
        feedFanOutService.backfillNewFriendship(event.userAId(), event.userBId());
    }

    /**
     * Only the deleted account's own timeline. Its plans are still entries in its
     * friends' timelines, and are left there: the read side already drops an entry with
     * no row behind it (see {@code FeedQueryService}), and finding them all would mean
     * knowing the friend list the delete has just removed.
     */
    @ApplicationModuleListener
    void onAccountDeleted(AccountDeletedEvent event) {
        timelineRepository.clear(event.userId());
    }
}
