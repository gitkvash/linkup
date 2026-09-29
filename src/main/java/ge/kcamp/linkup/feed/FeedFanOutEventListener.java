package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.identity.AccountDeletedEvent;
import ge.kcamp.linkup.social.FriendshipAcceptedEvent;
import ge.kcamp.linkup.social.GroupMemberAddedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

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
    private final ApplicationEventPublisher eventPublisher;

    FeedFanOutEventListener(
            FeedFanOutService feedFanOutService,
            RedisFeedTimelineRepository timelineRepository,
            ApplicationEventPublisher eventPublisher) {
        this.feedFanOutService = feedFanOutService;
        this.timelineRepository = timelineRepository;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onActivityCreated(ActivityCreatedEvent event) {
        // Ranked by when the activity happens, not when it was created: the feed is
        // ordered and paginated by start time, and scoring by creation instant meant
        // every cursor the client sent back pointed above the entire timeline.
        Set<UUID> reached = feedFanOutService.fanOutOnWrite(
                event.creatorId(), event.activityId(), event.startTime().toInstant(), event.groupId());
        // Published from here, after the write, and not by notification on the same
        // ActivityCreatedEvent: the two listeners run independently, and a signal that beat
        // the write made the client re-read a feed the plan wasn't in yet.
        eventPublisher.publishEvent(new FeedTimelinesUpdatedEvent(reached, Instant.now()));
    }

    /**
     * Safe to redeliver: a timeline is a sorted set keyed by activity, so pushing the
     * same plan twice leaves one entry with the same score.
     */
    @ApplicationModuleListener
    void onFriendshipAccepted(FriendshipAcceptedEvent event) {
        Set<UUID> gained = feedFanOutService.backfillNewFriendship(event.userAId(), event.userBId());
        if (!gained.isEmpty()) {
            eventPublisher.publishEvent(new FeedTimelinesUpdatedEvent(gained, Instant.now()));
        }
    }

    /** Safe to redeliver, for the same reason. */
    @ApplicationModuleListener
    void onGroupMemberAdded(GroupMemberAddedEvent event) {
        if (feedFanOutService.backfillGroupMember(event.groupId(), event.userId())) {
            eventPublisher.publishEvent(new FeedTimelinesUpdatedEvent(Set.of(event.userId()), Instant.now()));
        }
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
