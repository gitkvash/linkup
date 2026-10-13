package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.activity.ActivityDeletedEvent;
import ge.kcamp.linkup.activity.ActivityUpdatedEvent;
import ge.kcamp.linkup.identity.AccountDeletedEvent;
import ge.kcamp.linkup.social.FriendshipAcceptedEvent;
import ge.kcamp.linkup.social.FriendshipEndedEvent;
import ge.kcamp.linkup.social.GroupMemberAddedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
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
                event.creatorId(), event.activityId(), event.startTime().toInstant(),
                event.visibility(), event.groupId(), event.invitedUserIds());
        // Published from here, after the write, and not by notification on the same
        // ActivityCreatedEvent: the two listeners run independently, and a signal that beat
        // the write made the client re-read a feed the plan wasn't in yet.
        eventPublisher.publishEvent(new FeedTimelinesUpdatedEvent(reached, Instant.now()));
    }

    /**
     * An edit can move the start, which the entry is scored by, and change who the plan is
     * for. Re-pushing to the audience as it now stands re-scores the entries already there
     * (one entry per plan, so this moves rather than duplicates) and reaches anyone the
     * edit let in. Anyone it shut out keeps a stale entry until their next read drops it:
     * which timelines held it before is not recorded anywhere, and the read side already
     * removes entries the viewer may no longer see.
     * <p>
     * The host and the people in the plan are told by notification's own listener on this
     * event, so only the rest of the audience - whose feed card is now out of date - gets
     * the sync signal from here. Safe to redeliver for the same reason as a create.
     */
    @ApplicationModuleListener
    void onActivityUpdated(ActivityUpdatedEvent event) {
        Set<UUID> reached = new HashSet<>(feedFanOutService.fanOutOnWrite(
                event.creatorId(), event.activityId(), event.startTime().toInstant(),
                event.visibility(), event.groupId(), event.participantIds()));
        reached.remove(event.creatorId());
        if (event.participantIds() != null) {
            event.participantIds().forEach(reached::remove);
        }
        if (!reached.isEmpty()) {
            eventPublisher.publishEvent(new FeedTimelinesUpdatedEvent(reached, Instant.now()));
        }
    }

    /**
     * Takes the plan out of every timeline it was fanned out to, rather than leaving the
     * read side to drop it page by page. The host, who deleted it, needs no signal.
     * Removing an entry that is already gone is a no-op, so redelivery is safe.
     */
    @ApplicationModuleListener
    void onActivityDeleted(ActivityDeletedEvent event) {
        Set<UUID> removed = new HashSet<>(feedFanOutService.removeEverywhere(
                event.creatorId(), event.activityId(),
                event.visibility(), event.groupId(), event.participantIds()));
        removed.remove(event.creatorId());
        if (!removed.isEmpty()) {
            eventPublisher.publishEvent(new FeedTimelinesUpdatedEvent(removed, Instant.now()));
        }
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
     * Takes back what {@link #onFriendshipAccepted} and every fan-out since gave each side:
     * the other's plans that only the friendship made visible. Both sides already get a
     * feed sync signal from notification's listener on this event, and the read side
     * filters on visibility, so this keeps the timelines from carrying dead entries
     * rather than changing what either one sees. Idempotent.
     */
    @ApplicationModuleListener
    void onFriendshipEnded(FriendshipEndedEvent event) {
        feedFanOutService.pruneInvisible(event.userAId());
        feedFanOutService.pruneInvisible(event.userBId());
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
