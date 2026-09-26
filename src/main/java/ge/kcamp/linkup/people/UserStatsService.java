package ge.kcamp.linkup.people;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityHistoryService;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.people.dto.CategoryCountDto;
import ge.kcamp.linkup.people.dto.UserStatsDto;
import ge.kcamp.linkup.social.SocialGraphService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The caller's own year in plans. Only ever about the caller: there is no way to ask for
 * anyone else's, and nothing here takes a second user id.
 * <p>
 * Everything is bucketed in the caller's zone, which the client sends. The server has no
 * idea where anyone is, and a Saturday-night plan in Tbilisi is a Saturday afternoon in
 * UTC - the "evenings" in "mostly evenings" have to be theirs.
 */
@Service
@Transactional(readOnly = true)
public class UserStatsService {

    static final int MAX_COMPANIONS = 8;
    static final int MAX_PLACES = 5;

    private final ActivityHistoryService activityHistoryService;
    private final SocialGraphService socialGraphService;
    private final UserDirectoryService userDirectoryService;

    public UserStatsService(
            ActivityHistoryService activityHistoryService,
            SocialGraphService socialGraphService,
            UserDirectoryService userDirectoryService) {
        this.activityHistoryService = activityHistoryService;
        this.socialGraphService = socialGraphService;
        this.userDirectoryService = userDirectoryService;
    }

    public UserStatsDto stats(UUID userId, int year, ZoneId zone) {
        Instant from = startOfYear(year, zone);
        Instant to = startOfYear(year + 1, zone);
        List<ActivityFeedItem> plans = activityHistoryService.happenedBetween(userId, userId, from, to);

        int firstYear = userDirectoryService.memberSince(userId)
                .map(since -> since.atZone(zone).getYear())
                .orElse(year);

        UserStatsDto.Totals previous = null;
        if (year - 1 >= firstYear) {
            Instant previousFrom = startOfYear(year - 1, zone);
            previous = totals(
                    userId,
                    activityHistoryService.happenedBetween(userId, userId, previousFrom, from),
                    previousFrom,
                    from);
        }

        return new UserStatsDto(
                year,
                firstYear,
                totals(userId, plans, from, to),
                previous,
                byMonth(userId, plans, zone),
                byCategory(plans),
                topCompanions(userId, plans),
                byWeekday(plans, zone),
                byTimeOfDay(plans, zone),
                topPlaces(plans));
    }

    private UserStatsDto.Totals totals(UUID userId, List<ActivityFeedItem> plans, Instant from, Instant to) {
        long hosted = plans.stream().filter(plan -> userId.equals(plan.creatorId())).count();
        return new UserStatsDto.Totals(
                plans.size(), hosted, socialGraphService.countFriendsMadeBetween(userId, from, to));
    }

    private static List<UserStatsDto.MonthCount> byMonth(
            UUID userId, List<ActivityFeedItem> plans, ZoneId zone) {
        long[] hosted = new long[12];
        long[] joined = new long[12];
        for (ActivityFeedItem plan : plans) {
            int month = local(plan, zone).getMonthValue() - 1;
            if (userId.equals(plan.creatorId())) {
                hosted[month]++;
            } else {
                joined[month]++;
            }
        }
        List<UserStatsDto.MonthCount> months = new ArrayList<>(12);
        for (int month = 0; month < 12; month++) {
            months.add(new UserStatsDto.MonthCount(hosted[month], joined[month]));
        }
        return months;
    }

    private static List<CategoryCountDto> byCategory(List<ActivityFeedItem> plans) {
        Map<ActivityCategory, Long> counts = new EnumMap<>(ActivityCategory.class);
        plans.forEach(plan -> counts.merge(plan.category(), 1L, Long::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<ActivityCategory, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new CategoryCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<UserStatsDto.Companion> topCompanions(UUID userId, List<ActivityFeedItem> plans) {
        Map<UUID, Long> counts = new HashMap<>();
        activityHistoryService
                .companions(plans.stream().map(ActivityFeedItem::activityId).toList(), userId)
                .values()
                .forEach(people -> people.forEach(person -> counts.merge(person, 1L, Long::sum)));

        List<Map.Entry<UUID, Long>> top = counts.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().toString()))
                .limit(MAX_COMPANIONS)
                .toList();
        Map<UUID, UserSummary> people = userDirectoryService.findByIds(top.stream().map(Map.Entry::getKey).toList());
        return top.stream()
                // An account deleted since has no name to show and no profile to open.
                .filter(entry -> people.containsKey(entry.getKey()))
                .map(entry -> new UserStatsDto.Companion(people.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private static List<Long> byWeekday(List<ActivityFeedItem> plans, ZoneId zone) {
        Long[] days = new Long[7];
        Arrays.fill(days, 0L);
        plans.forEach(plan -> days[local(plan, zone).getDayOfWeek().getValue() - 1]++);
        return List.of(days);
    }

    private static UserStatsDto.TimeOfDay byTimeOfDay(List<ActivityFeedItem> plans, ZoneId zone) {
        long morning = 0;
        long afternoon = 0;
        long evening = 0;
        for (ActivityFeedItem plan : plans) {
            if (!plan.hasTime()) {
                continue;
            }
            int hour = local(plan, zone).getHour();
            if (hour < 12) {
                morning++;
            } else if (hour < 17) {
                afternoon++;
            } else {
                evening++;
            }
        }
        return new UserStatsDto.TimeOfDay(morning, afternoon, evening);
    }

    /**
     * Grouped by the place line as written, ignoring case and surrounding space. There is
     * no place id on a plan to group by, and two plans at "Mtatsminda Park, Tbilisi" are
     * the same place whoever typed it.
     */
    static List<UserStatsDto.PlaceCount> topPlaces(List<ActivityFeedItem> plans) {
        Map<String, String> firstSpelling = new LinkedHashMap<>();
        Map<String, Long> counts = new HashMap<>();
        for (ActivityFeedItem plan : plans) {
            String address = plan.addressText() == null ? "" : plan.addressText().strip();
            if (address.isEmpty()) {
                continue;
            }
            String key = address.toLowerCase(Locale.ROOT);
            firstSpelling.putIfAbsent(key, address);
            counts.merge(key, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_PLACES)
                .map(entry -> {
                    String address = firstSpelling.get(entry.getKey());
                    int comma = address.indexOf(',');
                    String name = comma < 0 ? address : address.substring(0, comma).strip();
                    String area = comma < 0 ? null : address.substring(comma + 1).strip();
                    return new UserStatsDto.PlaceCount(
                            name, area == null || area.isEmpty() ? null : area, entry.getValue());
                })
                .toList();
    }

    private static ZonedDateTime local(ActivityFeedItem plan, ZoneId zone) {
        return plan.startTime().withZoneSameInstant(zone);
    }

    private static Instant startOfYear(int year, ZoneId zone) {
        return LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant();
    }
}
