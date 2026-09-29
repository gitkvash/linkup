package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityQueryService;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.SocialGraphService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
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
     * @param groupId set for a GROUP-visibility plan, null otherwise. Its members receive
     *                the item instead of the creator's friends: the two lists overlap only
     *                by coincidence, and a group is the one audience deliberately defined
     *                as something other than a friend list. Without this branch a group
     *                plan reached the map and a direct link but never the feed, which is
     *                where people actually look. A friend who is not in the group would in
     *                any case have it filtered back out on read by the visibility
     *                predicate, so pushing to them only buys short pages.
     */
    Set<UUID> fanOutOnWrite(UUID creatorId, UUID activityId, Instant startTime, UUID groupId) {
        double score = FeedTimelineScore.of(activityId, startTime);
        timelineRepository.push(creatorId, activityId, score);
        Set<UUID> reached = new HashSet<>();
        reached.add(creatorId);

        if (groupId != null) {
            // No influencer exemption here: membership is bounded by whoever was added by
            // hand, so there is no write-amplification tail to protect against.
            Collection<UUID> members = groupService.memberIds(groupId);
            timelineRepository.pushAll(members, activityId, score);
            reached.addAll(members);
            return reached;
        }

        if (isInfluencer(creatorId)) {
            return reached;
        }
        Collection<UUID> friends = socialGraphService.getAcceptedFriendIds(creatorId);
        timelineRepository.pushAll(friends, activityId, score);
        reached.addAll(friends);
        return reached;
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
            if (item.status().isOver()) {
                continue;
            }
            timelineRepository.push(friendId, item.activityId(),
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
