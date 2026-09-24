package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityQueryService;
import ge.kcamp.linkup.feed.dto.FeedItemDto;
import ge.kcamp.linkup.feed.dto.FeedPageDto;
import ge.kcamp.linkup.identity.UserDirectoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FeedQueryService {

    private static final int INFLUENCER_LOOKBACK_DAYS = 30;
    static final int MAX_LIMIT = 50;

    private final RedisFeedTimelineRepository timelineRepository;
    private final ActivityQueryService activityQueryService;
    private final FeedFanOutService feedFanOutService;
    private final UserDirectoryService userDirectoryService;

    public FeedQueryService(
            RedisFeedTimelineRepository timelineRepository,
            ActivityQueryService activityQueryService,
            FeedFanOutService feedFanOutService,
            UserDirectoryService userDirectoryService) {
        this.timelineRepository = timelineRepository;
        this.activityQueryService = activityQueryService;
        this.feedFanOutService = feedFanOutService;
        this.userDirectoryService = userDirectoryService;
    }

    /**
     * One page of the user's feed, ordered newest-start-first.
     *
     * @param cursor opaque token from the previous page's {@code nextCursor}; null for
     *               the first page. It is a {@link FeedTimelineScore}, not a timestamp -
     *               clients must only echo it back.
     */
    @Transactional(readOnly = true)
    public FeedPageDto getFeed(UUID userId, Long cursor, int limit) {
        int pageSize = Math.clamp(limit, 1, MAX_LIMIT);
        Double cursorScore = cursor == null ? null : cursor.doubleValue();

        List<ActivityFeedItem> timelineItems = activityQueryService.findByIds(
                timelineRepository.read(userId, cursorScore, pageSize), userId);

        List<ActivityFeedItem> influencerItems = influencerItems(userId, cursorScore, pageSize);

        // Timeline first so a duplicate keeps the timeline copy; both are the same row.
        LinkedHashMap<UUID, ActivityFeedItem> deduped = new LinkedHashMap<>();
        timelineItems.forEach(item -> deduped.putIfAbsent(item.activityId(), item));
        influencerItems.forEach(item -> deduped.putIfAbsent(item.activityId(), item));

        List<ActivityFeedItem> ordered = new ArrayList<>(deduped.values());
        ordered.sort(Comparator.comparingDouble(FeedQueryService::scoreOf).reversed());

        List<ActivityFeedItem> page =
                ordered.size() > pageSize ? ordered.subList(0, pageSize) : ordered;

        // One batch lookup for the whole page rather than a query per card.
        Map<UUID, String> creatorNames = userDirectoryService.namesFor(
                page.stream().map(ActivityFeedItem::creatorId).distinct().toList());

        List<FeedItemDto> items = page.stream()
                .map(item -> toDto(item, creatorNames.get(item.creatorId())))
                .toList();

        // Only advertise a cursor when a full page came back. Returning one for any
        // non-empty page meant the client always made one more request than necessary
        // and left a trailing spinner on screen forever.
        Long nextCursor = page.size() < pageSize
                ? null
                : (long) scoreOf(page.get(page.size() - 1));

        return new FeedPageDto(items, nextCursor);
    }

    /**
     * Fan-out-on-read half: activities by friends who are too well-connected to fan out
     * on write. These used to ignore the cursor entirely, so the same set was re-fetched
     * and re-merged into every page.
     */
    private List<ActivityFeedItem> influencerItems(UUID userId, Double cursorScore, int pageSize) {
        List<UUID> friendIds = feedFanOutService.acceptedFriendIds(userId);
        Set<UUID> influencerIds = feedFanOutService.influencersAmong(friendIds);
        if (influencerIds.isEmpty()) {
            return List.of();
        }

        Instant lookback = Instant.now().minus(INFLUENCER_LOOKBACK_DAYS, ChronoUnit.DAYS);
        Instant notAfter = cursorScore == null
                ? null
                : Instant.ofEpochMilli(FeedTimelineScore.startMillisOf(cursorScore));

        return activityQueryService.findByCreatorIn(List.copyOf(influencerIds), lookback, userId).stream()
                // The SQL bound is start-time granular; compare exact scores here so an
                // item sharing the cursor's millisecond is neither repeated nor skipped.
                .filter(item -> notAfter == null || !item.startTime().toInstant().isAfter(notAfter))
                .filter(item -> cursorScore == null || scoreOf(item) < cursorScore)
                .sorted(Comparator.comparingDouble(FeedQueryService::scoreOf).reversed())
                .limit(pageSize)
                .toList();
    }

    private static double scoreOf(ActivityFeedItem item) {
        return FeedTimelineScore.of(item.activityId(), item.startTime().toInstant());
    }

    private static FeedItemDto toDto(ActivityFeedItem item, String creatorUsername) {
        return new FeedItemDto(
                item.activityId(),
                item.creatorId(),
                creatorUsername,
                item.title(),
                item.startTime(),
                item.addressText(),
                item.status(),
                item.category(),
                item.hasTime(),
                item.activityType(),
                item.repeatFrequency(),
                item.repeatInterval());
    }
}
