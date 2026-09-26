package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.query.ActivityHistoryRepository;
import ge.kcamp.linkup.activity.query.ActivityQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a person has done and is about to do, for a profile and for the stats screen -
 * part of the {@code activity} module's public API.
 * <p>
 * Candidates come from {@link ActivityHistoryRepository}, with loose SQL bounds; the rows
 * come from {@link ActivityQueryRepository#findByIds}, which resolves each plan's status;
 * and the status is what decides. "Happened" is {@link ActivityStatus#ENDED} - a plan the
 * host started and that finished - so a plan nobody started, or one that was cancelled,
 * counts for nothing here. That rule lives in {@link ActivityStatusResolver} and is not
 * restated in SQL.
 */
@Service
@Transactional(readOnly = true)
public class ActivityHistoryService {

    /** {@link ActivityQueryRepository#findByIds} reads at most this many rows at once. */
    private static final int BATCH = 200;

    private final ActivityHistoryRepository historyRepository;
    private final ActivityQueryRepository queryRepository;

    public ActivityHistoryService(
            ActivityHistoryRepository historyRepository, ActivityQueryRepository queryRepository) {
        this.historyRepository = historyRepository;
        this.queryRepository = queryRepository;
    }

    /**
     * {@code personId}'s plans - hosting or going - that {@code viewerId} may see and that
     * haven't happened yet or are happening now, soonest first.
     */
    public List<ActivityFeedItem> upcomingFor(UUID personId, UUID viewerId) {
        return read(historyRepository.findPossiblyUpcoming(personId, viewerId), viewerId).stream()
                .filter(item -> item.status() == ActivityStatus.UPCOMING
                        || item.status() == ActivityStatus.LIVE)
                .sorted(Comparator.comparing(ActivityFeedItem::startTime))
                .toList();
    }

    /** Plans the two both took part in and that happened, newest first. */
    public List<ActivityFeedItem> happenedTogether(UUID viewerId, UUID otherId) {
        if (viewerId.equals(otherId)) {
            return List.of();
        }
        return happened(historyRepository.findStartedTogether(viewerId, otherId), viewerId).stream()
                .sorted(Comparator.comparing(ActivityFeedItem::startTime).reversed())
                .toList();
    }

    /**
     * Plans {@code personId} took part in, starting in {@code [from, to)}, that happened -
     * as far as {@code viewerId} may see them. Pass the same id twice for your own.
     */
    public List<ActivityFeedItem> happenedBetween(UUID personId, UUID viewerId, Instant from, Instant to) {
        return happened(historyRepository.findTookPartBetween(personId, viewerId, from, to), viewerId);
    }

    /** Everyone else who was in each of these plans, by plan. Plans with nobody else are absent. */
    public Map<UUID, List<UUID>> companions(Collection<UUID> activityIds, UUID userId) {
        return historyRepository.findCompanions(activityIds, userId);
    }

    private List<ActivityFeedItem> happened(List<UUID> candidates, UUID viewerId) {
        return read(candidates, viewerId).stream()
                .filter(item -> item.status() == ActivityStatus.ENDED)
                .toList();
    }

    private List<ActivityFeedItem> read(List<UUID> ids, UUID viewerId) {
        List<ActivityFeedItem> items = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += BATCH) {
            items.addAll(queryRepository.findByIds(
                    ids.subList(from, Math.min(from + BATCH, ids.size())), viewerId));
        }
        return items;
    }
}
