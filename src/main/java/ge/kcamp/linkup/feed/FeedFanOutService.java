package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.SocialGraphService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
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

    private final RedisFeedTimelineRepository timelineRepository;
    private final SocialGraphService socialGraphService;
    private final GroupService groupService;
    private final long influencerThreshold;

    FeedFanOutService(
            RedisFeedTimelineRepository timelineRepository,
            SocialGraphService socialGraphService,
            GroupService groupService,
            @Value("${linkup.feed.influencer-threshold}") long influencerThreshold) {
        this.timelineRepository = timelineRepository;
        this.socialGraphService = socialGraphService;
        this.groupService = groupService;
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
    void fanOutOnWrite(UUID creatorId, UUID activityId, Instant startTime, UUID groupId) {
        double score = FeedTimelineScore.of(activityId, startTime);
        timelineRepository.push(creatorId, activityId, score);

        if (groupId != null) {
            // No influencer exemption here: membership is bounded by whoever was added by
            // hand, so there is no write-amplification tail to protect against.
            timelineRepository.pushAll(groupService.memberIds(groupId), activityId, score);
            return;
        }

        if (isInfluencer(creatorId)) {
            return;
        }
        timelineRepository.pushAll(socialGraphService.getAcceptedFriendIds(creatorId), activityId, score);
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

    /** Exposed for the query side, which needs the same friend list it fans out to. */
    List<UUID> acceptedFriendIds(UUID userId) {
        return socialGraphService.getAcceptedFriendIds(userId);
    }
}
