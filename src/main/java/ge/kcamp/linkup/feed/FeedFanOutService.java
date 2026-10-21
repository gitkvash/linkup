package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityQueryService;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.SocialGraphService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hybrid fan-out: ordinary users get Fan-Out on Write (their friends' Redis timelines are
 * updated immediately); "influencer" users (many friends) are excluded from the write
 * fan-out to avoid write amplification - their followers pull their activities on read
 * instead (see {@link FeedQueryService}).
 */
@Service
class FeedFanOutService {

    /**
     * How far back a new friendship looks for the other side's plans. Ended ones are
     * dropped anyway; the window is there for a repeating plan, whose stored start is its
     * first occurrence and can be weeks old while the plan is still on.
     */
    private static final Duration BACKFILL_LOOKBACK = Duration.ofDays(30);

    private final RedisFeedTimelineRepository timelineRepository;
    private final SocialGraphService socialGraphService;
    private final GroupService groupService;
    private final ActivityQueryService activityQueryService;
    private final long influencerThreshold;

    FeedFanOutService(
            RedisFeedTimelineRepository timelineRepository,
            SocialGraphService socialGraphService,
            GroupService groupService,
            ActivityQueryService activityQueryService,
            @Value("${linkup.feed.influencer-threshold}") long influencerThreshold) {
        this.timelineRepository = timelineRepository;
        this.socialGraphService = socialGraphService;
        this.groupService = groupService;
        this.activityQueryService = activityQueryService;
        this.influencerThreshold = influencerThreshold;
    }

    /**
     * Writes a plan into the timeline of everyone it is for, scored by its start. Also
     * the re-score after an edit: a sorted set keeps one entry per plan, so pushing again
     * moves it rather than duplicating it.
     *
     * @return everyone whose timeline was written
     */
    Set<UUID> fanOutOnWrite(
            UUID creatorId, UUID activityId, Instant startTime,
            ActivityVisibility visibility, UUID groupId, Collection<UUID> participantIds) {
        Set<UUID> audience = audienceOf(creatorId, visibility, groupId, participantIds);
        timelineRepository.pushAll(audience, activityId, FeedTimelineScore.of(activityId, startTime));
        return audience;
    }

    /**
     * Takes a deleted plan out of every timeline {@link #fanOutOnWrite} put it in. The
     * read side would drop each entry anyway once it found no row behind it, but only when
     * that page was read, and only after a feed re-read the client had no reason to make.
     *
     * @return everyone whose timeline may have held it
     */
    Set<UUID> removeEverywhere(
            UUID creatorId, UUID activityId,
            ActivityVisibility visibility, UUID groupId, Collection<UUID> participantIds) {
        Set<UUID> audience = audienceOf(creatorId, visibility, groupId, participantIds);
        timelineRepository.removeFromAll(audience, activityId);
        return audience;
    }

    /**
     * Whose timeline a plan belongs in: its creator and everyone with a participant row,
     * always (a row of any status makes a plan visible), and then by visibility:
     * <ul>
     *   <li>PRIVATE - nobody else. Pushing it to every friend, as this once did, was
     *       filtered back out on read, but still woke each of them with a sync signal.</li>
     *   <li>GROUP - the group's members instead of the creator's friends: the two lists
     *       overlap only by coincidence, and a group is the one audience deliberately
     *       defined as something other than a friend list. No influencer exemption -
     *       membership is bounded by whoever was added by hand.</li>
     *   <li>FRIENDS, PUBLIC - the creator's friends, unless the creator is an influencer,
     *       whose plans are pulled on read instead. A null visibility (an event recorded
     *       before it was carried) is treated the same, as it was before.</li>
     * </ul>
     * A friend outside the plan's audience would have it filtered back out on read by the
     * visibility predicate in any case; this only decides who pays for the write.
     */
    private Set<UUID> audienceOf(
            UUID creatorId, ActivityVisibility visibility, UUID groupId, Collection<UUID> participantIds) {
        Set<UUID> audience = new LinkedHashSet<>();
        audience.add(creatorId);
        if (participantIds != null) {
            audience.addAll(participantIds);
        }
        if (visibility == ActivityVisibility.PRIVATE) {
            return audience;
        }
        if (groupId != null) {
            audience.addAll(groupService.memberIds(groupId));
            return audience;
        }
        if (!isInfluencer(creatorId)) {
            audience.addAll(socialGraphService.getAcceptedFriendIds(creatorId));
        }
        return audience;
    }

    /**
     * Drops every entry in a user's timeline they may no longer see - after a friendship
     * ended, the ex-friend's FRIENDS plans. One Redis read and one ids-only query, so
     * the visibility rule stays the activity module's; a plan the user is still in, or
     * shares through a group, survives as it would on read.
     *
     * @return whether anything was removed
     */
    boolean pruneInvisible(UUID userId) {
        List<UUID> entries = timelineRepository.readAll(userId);
        if (entries.isEmpty()) {
            return false;
        }
        Set<UUID> visible = activityQueryService.findVisibleIds(entries, userId);
        List<UUID> gone = entries.stream().filter(id -> !visible.contains(id)).toList();
        timelineRepository.remove(userId, gone);
        return !gone.isEmpty();
    }

    /**
     * Gives each side of a new friendship the other's plans that are still on.
     * <p>
     * Fan-out happens once, when a plan is created, so a plan made before two people
     * became friends never reached the new friend's feed - it was on the map, where
     * "Anyone" put it, and nowhere they actually look. Ended plans stay out: accepting a
     * request shouldn't fill a feed with last month. Visibility is the viewer's, as on
     * any read ({@code findByCreatorIn} applies it), since this runs on a listener thread
     * as SYSTEM, which RLS does not filter. An influencer's plans are skipped here as at
     * creation; the read side pulls those in.
     *
     * @return the sides that actually gained a plan, so only they are told to re-read
     */
    Set<UUID> backfillNewFriendship(UUID userAId, UUID userBId) {
        Set<UUID> gained = new HashSet<>();
        if (backfill(userAId, userBId)) {
            gained.add(userBId);
        }
        if (backfill(userBId, userAId)) {
            gained.add(userAId);
        }
        return gained;
    }

    private boolean backfill(UUID creatorId, UUID friendId) {
        if (isInfluencer(creatorId)) {
            return false;
        }
        boolean pushed = false;
        Instant after = Instant.now().minus(BACKFILL_LOOKBACK);
        for (ActivityFeedItem item : activityQueryService.findByCreatorIn(List.of(creatorId), after, friendId)) {
            // A plan waiting on its host's "did it happen?" is past, not still on.
            if (item.status().isOver() || item.needsCheckIn()) {
                continue;
            }
            timelineRepository.push(friendId, item.activityId(),
                    FeedTimelineScore.of(item.activityId(), item.startTime().toInstant()));
            pushed = true;
        }
        return pushed;
    }

    /**
     * Gives someone just added to a group the group's plans that are still on. Fan-out
     * happens once, when a plan is created, so without this a new member saw only the
     * plans made after they joined. Visibility is the new member's, as in
     * {@link #backfillNewFriendship}, and ended plans stay out.
     *
     * @return whether any plan was added, so the member is only told to re-read if so
     */
    boolean backfillGroupMember(UUID groupId, UUID userId) {
        Instant after = Instant.now().minus(BACKFILL_LOOKBACK);
        boolean pushed = false;
        for (ActivityFeedItem item : activityQueryService.findByGroup(groupId, after, userId)) {
            // A plan waiting on its host's "did it happen?" is past, not still on.
            if (item.status().isOver() || item.needsCheckIn()) {
                continue;
            }
            timelineRepository.push(userId, item.activityId(),
                    FeedTimelineScore.of(item.activityId(), item.startTime().toInstant()));
            pushed = true;
        }
        return pushed;
    }

    boolean isInfluencer(UUID userId) {
        return socialGraphService.countAcceptedFriends(userId) >= influencerThreshold;
    }

    /**
     * Batch form of {@link #isInfluencer}. Filtering a friend list one call at a time
     * cost a query per friend, each loading that friend's entire friendship set - the
     * feed's single worst hotspot.
     */
    Set<UUID> influencersAmong(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return socialGraphService.countAcceptedFriendsFor(userIds).entrySet().stream()
                .filter(entry -> entry.getValue() >= influencerThreshold)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
