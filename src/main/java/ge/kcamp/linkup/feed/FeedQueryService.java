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
import java.util.stream.Collectors;

@Service
public class FeedQueryService {

    private static final int INFLUENCER_LOOKBACK_DAYS = 30;
    static final int MAX_LIMIT = 50;

    /** Timeline reads per page before giving up on filling it: one Redis call and one query each. */
    private static final int MAX_TIMELINE_READS = 4;

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

        TimelineScan timeline = scanTimeline(userId, cursorScore, pageSize);
        List<ActivityFeedItem> pulled = pulledItems(userId, cursorScore, pageSize);

        // Timeline first so a duplicate keeps the timeline copy; both are the same row.
        LinkedHashMap<UUID, ActivityFeedItem> deduped = new LinkedHashMap<>();
        timeline.items().forEach(item -> deduped.putIfAbsent(item.activityId(), item));
        pulled.forEach(item -> deduped.putIfAbsent(item.activityId(), item));

        // Nothing below the part of the timeline this page actually scanned: an item from
        // the pulled half down there would be served now, and then the cursor would have
        // to skip the unscanned timeline entries above it to avoid serving it twice.
        List<ActivityFeedItem> ordered = new ArrayList<>(deduped.values().stream()
                .filter(item -> scoreOf(item) >= timeline.floor())
                .toList());
        ordered.sort(Comparator.comparingDouble(FeedQueryService::scoreOf).reversed());

        List<ActivityFeedItem> page =
                ordered.size() > pageSize ? ordered.subList(0, pageSize) : ordered;

        // One batch lookup for the whole page rather than a query per card.
        Map<UUID, String> creatorNames = userDirectoryService.namesFor(
                page.stream().map(ActivityFeedItem::creatorId).distinct().toList());

        List<FeedItemDto> items = page.stream()
                .map(item -> toDto(item, creatorNames.get(item.creatorId())))
                .toList();

        // A full page advertises the next one. A short page does too while the timeline
        // still has entries below the part scanned: "short" used to be read as "the end",
        // and it was measured after dropping entries the viewer can't see or that were
        // deleted - so one such entry among the newest twenty cut the feed off there, and
        // everything older was unreachable. A short page with the timeline exhausted is
        // the end, and advertising a cursor then left a trailing spinner on screen forever.
        Long nextCursor;
        if (page.size() == pageSize) {
            nextCursor = (long) scoreOf(page.get(page.size() - 1));
        } else if (timeline.floor() != Double.NEGATIVE_INFINITY) {
            nextCursor = (long) timeline.floor();
        } else {
            nextCursor = null;
        }

        return new FeedPageDto(items, nextCursor);
    }

    /**
     * What this page found in the timeline, and how far down it looked: {@code floor} is
     * the score of the last entry read, or negative infinity once the timeline ran out.
     */
    private record TimelineScan(List<ActivityFeedItem> items, double floor) {
    }

    /**
     * Reads the timeline until a page's worth of entries resolves, the timeline runs out,
     * or {@link #MAX_TIMELINE_READS} reads have gone by. Entries with no row the viewer may
     * see - a deleted plan, a group they have left - are dropped from the timeline as they
     * are found, so the next read doesn't pay for them again.
     */
    private TimelineScan scanTimeline(UUID userId, Double cursorScore, int pageSize) {
        List<ActivityFeedItem> items = new ArrayList<>();
        Double before = cursorScore;
        for (int read = 0; read < MAX_TIMELINE_READS; read++) {
            List<RedisFeedTimelineRepository.Entry> entries =
                    timelineRepository.readScored(userId, before, pageSize);
            if (!entries.isEmpty()) {
                List<ActivityFeedItem> found = activityQueryService.findByIds(
                        entries.stream().map(RedisFeedTimelineRepository.Entry::activityId).toList(), userId);
                Set<UUID> foundIds = found.stream().map(ActivityFeedItem::activityId).collect(Collectors.toSet());
                timelineRepository.remove(userId, entries.stream()
                        .map(RedisFeedTimelineRepository.Entry::activityId)
                        .filter(id -> !foundIds.contains(id))
                        .toList());
                items.addAll(found);
            }
            if (entries.size() < pageSize) {
                return new TimelineScan(items, Double.NEGATIVE_INFINITY);
            }
            before = entries.get(entries.size() - 1).score();
            if (items.size() >= pageSize) {
                break;
            }
        }
        return new TimelineScan(items, before);
    }

    /**
     * Fan-out-on-read half: the viewer's own plans, and those of friends too
     * well-connected to fan out on write.
     * <p>
     * Own plans are read here as well as pushed, because the push is asynchronous. The
     * client reloads the feed as soon as a create returns, which can be before the
     * listener has written the timeline - so a plan the user had just made showed under
     * Mine, which reads Postgres, and not under All, until the next refresh.
     */
    private List<ActivityFeedItem> pulledItems(UUID userId, Double cursorScore, int pageSize) {
        List<UUID> friendIds = feedFanOutService.acceptedFriendIds(userId);
        List<UUID> creatorIds = new ArrayList<>(feedFanOutService.influencersAmong(friendIds));
        creatorIds.add(userId);

        Instant lookback = Instant.now().minus(INFLUENCER_LOOKBACK_DAYS, ChronoUnit.DAYS);
        // Bounds the SQL as well as the stream below: the query returns at most 200 rows,
        // newest first, so without an upper bound every page past the first re-read the
        // same newest 200 and nothing older was ever served.
        Instant notAfter = cursorScore == null
                ? null
                : Instant.ofEpochMilli(FeedTimelineScore.startMillisOf(cursorScore));

        return activityQueryService.findByCreatorIn(creatorIds, lookback, notAfter, userId).stream()
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
