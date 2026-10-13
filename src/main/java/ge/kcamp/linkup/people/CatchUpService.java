package ge.kcamp.linkup.people;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityHistoryService;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.people.dto.CatchUpDto;
import ge.kcamp.linkup.social.SocialGraphService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Haven't seen them in a while": the caller's friends they have done plans with before,
 * but not lately and with nothing coming up. Only ever about the caller.
 * <p>
 * Made entirely of what the app already knows - the plans that happened, who else was in
 * them, and what is still ahead - so there is nothing to store and nothing to go stale:
 * the list is a question asked fresh each time.
 * <ul>
 *   <li>Only friends with at least one plan in common in the last {@link #LOOK_BACK}. Someone
 *       the caller has never done anything with is not "drifted", and nobody past the window is
 *       news any more.</li>
 *   <li>Quiet for at least {@link #QUIET_AFTER}.</li>
 *   <li>Not anyone muted: that is a request to hear less about them.</li>
 *   <li>Not anyone they are already in a plan with that hasn't finished. Repeating plans
 *       count, which is the point: a weekly run is not a lapsed friendship.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class CatchUpService {

    static final Duration LOOK_BACK = Duration.ofDays(365);
    static final Duration QUIET_AFTER = Duration.ofDays(28);
    static final int MAX_LIMIT = 10;

    private final ActivityHistoryService activityHistoryService;
    private final SocialGraphService socialGraphService;
    private final UserDirectoryService userDirectoryService;

    public CatchUpService(
            ActivityHistoryService activityHistoryService,
            SocialGraphService socialGraphService,
            UserDirectoryService userDirectoryService) {
        this.activityHistoryService = activityHistoryService;
        this.socialGraphService = socialGraphService;
        this.userDirectoryService = userDirectoryService;
    }

    public List<CatchUpDto> catchUp(UUID userId, int limit) {
        return catchUp(userId, limit, Instant.now());
    }

    List<CatchUpDto> catchUp(UUID userId, int limit, Instant now) {
        SocialGraphService.AcceptedFriends friends = socialGraphService.getAcceptedFriends(userId);
        Set<UUID> eligible = new HashSet<>(friends.ids());
        eligible.removeAll(friends.muted());
        if (eligible.isEmpty()) {
            return List.of();
        }

        List<ActivityFeedItem> happened = activityHistoryService.happenedBetween(
                userId, userId, now.minus(LOOK_BACK), now);
        if (happened.isEmpty()) {
            return List.of();
        }

        Set<UUID> alreadyPlanned = new HashSet<>();
        List<UUID> upcomingIds = activityHistoryService.upcomingFor(userId, userId).stream()
                .map(ActivityFeedItem::activityId)
                .toList();
        activityHistoryService.companions(upcomingIds, userId).values().forEach(alreadyPlanned::addAll);

        Map<UUID, List<UUID>> companions = activityHistoryService.companions(
                happened.stream().map(ActivityFeedItem::activityId).toList(), userId);

        Map<UUID, Together> byFriend = new HashMap<>();
        for (ActivityFeedItem plan : happened) {
            for (UUID companion : companions.getOrDefault(plan.activityId(), List.of())) {
                if (eligible.contains(companion) && !alreadyPlanned.contains(companion)) {
                    byFriend.computeIfAbsent(companion, id -> new Together()).add(plan);
                }
            }
        }

        Instant quietBefore = now.minus(QUIET_AFTER);
        List<Map.Entry<UUID, Together>> lapsed = byFriend.entrySet().stream()
                .filter(entry -> entry.getValue().last.startTime().toInstant().isBefore(quietBefore))
                // Closest first: five plans and a lapse says more than one plan and a lapse.
                // Then whoever has been quiet longest.
                .sorted(Comparator
                        .<Map.Entry<UUID, Together>>comparingLong(entry -> -entry.getValue().count)
                        .thenComparing(entry -> entry.getValue().last.startTime().toInstant()))
                .limit(Math.clamp(limit, 1, MAX_LIMIT))
                .toList();

        Map<UUID, UserSummary> users = userDirectoryService.findByIds(
                lapsed.stream().map(Map.Entry::getKey).toList());
        return lapsed.stream()
                .filter(entry -> users.containsKey(entry.getKey()))
                .map(entry -> {
                    ActivityFeedItem last = entry.getValue().last;
                    return new CatchUpDto(
                            users.get(entry.getKey()),
                            last.startTime(),
                            ChronoUnit.DAYS.between(last.startTime().toInstant(), now),
                            entry.getValue().count,
                            last.title(),
                            last.category());
                })
                .toList();
    }

    /** One friend's running total while the plans are walked. */
    private static final class Together {
        long count;
        ActivityFeedItem last;

        void add(ActivityFeedItem plan) {
            count++;
            if (last == null || plan.startTime().isAfter(last.startTime())) {
                last = plan;
            }
        }
    }
}
