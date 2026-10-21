package ge.kcamp.linkup.people;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityHistoryService;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.people.dto.CatchUpDto;
import ge.kcamp.linkup.social.SocialGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who shows up under "haven't seen them in a while", and in what order. */
class CatchUpServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    private final UUID me = UUID.randomUUID();
    private final UUID nika = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final UUID luka = UUID.randomUUID();

    private ActivityHistoryService history;
    private SocialGraphService social;
    private CatchUpService service;

    @BeforeEach
    void setUp() {
        history = mock(ActivityHistoryService.class);
        social = mock(SocialGraphService.class);
        UserDirectoryService users = mock(UserDirectoryService.class);
        service = new CatchUpService(history, social, users);

        when(social.getAcceptedFriends(me))
                .thenReturn(new SocialGraphService.AcceptedFriends(List.of(nika, ana, luka), Set.of()));
        when(users.findByIds(anyCollection())).thenAnswer(invocation -> {
            Map<UUID, UserSummary> found = new java.util.HashMap<>();
            for (Object id : (java.util.Collection<?>) invocation.getArgument(0)) {
                found.put((UUID) id, new UserSummary((UUID) id, "u" + id.toString().substring(0, 4), null, null));
            }
            return found;
        });
        when(history.upcomingFor(me, me)).thenReturn(List.of());
        when(history.companions(anyCollection(), eq(me))).thenAnswer(invocation -> {
            Map<UUID, List<UUID>> answer = new java.util.HashMap<>();
            for (Object id : (java.util.Collection<?>) invocation.getArgument(0)) {
                if (togetherAnswers.containsKey(id)) {
                    answer.put((UUID) id, togetherAnswers.get(id));
                }
            }
            return answer;
        });
    }

    @Test
    void aFriendNotSeenForAMonthIsListedWithTheLastPlan() {
        ActivityFeedItem hike = plan("Hike", ActivityCategory.HIKING, daysAgo(40));
        ActivityFeedItem dinner = plan("Dinner", ActivityCategory.FOOD_AND_DRINK, daysAgo(90));
        happened(hike, dinner);
        together(hike, nika);
        together(dinner, nika);

        List<CatchUpDto> result = service.catchUp(me, 5, NOW);

        assertThat(result).hasSize(1);
        CatchUpDto line = result.getFirst();
        assertThat(line.user().userId()).isEqualTo(nika);
        assertThat(line.lastTitle()).isEqualTo("Hike");
        assertThat(line.lastCategory()).isEqualTo(ActivityCategory.HIKING);
        assertThat(line.plansTogether()).isEqualTo(2);
        assertThat(line.daysSince()).isEqualTo(40);
    }

    @Test
    void someoneSeenRecentlyIsNotListed() {
        ActivityFeedItem lastWeek = plan("Coffee", ActivityCategory.FOOD_AND_DRINK, daysAgo(6));
        happened(lastWeek);
        together(lastWeek, nika);

        assertThat(service.catchUp(me, 5, NOW)).isEmpty();
    }

    @Test
    void aMutedFriendIsLeftOut() {
        when(social.getAcceptedFriends(me))
                .thenReturn(new SocialGraphService.AcceptedFriends(List.of(nika, ana), Set.of(nika)));
        ActivityFeedItem old = plan("Hike", ActivityCategory.HIKING, daysAgo(60));
        happened(old);
        together(old, nika, ana);

        assertThat(service.catchUp(me, 5, NOW)).extracting(line -> line.user().userId()).containsExactly(ana);
    }

    @Test
    void someoneYoureAlreadyInAPlanWithIsLeftOut() {
        ActivityFeedItem old = plan("Hike", ActivityCategory.HIKING, daysAgo(60));
        happened(old);
        together(old, nika, ana);
        ActivityFeedItem coming = plan("Weekly run", ActivityCategory.RUNNING, daysAgo(200));
        when(history.upcomingFor(me, me)).thenReturn(List.of(coming));
        together(coming, nika);

        assertThat(service.catchUp(me, 5, NOW)).extracting(line -> line.user().userId()).containsExactly(ana);
    }

    @Test
    void thoseYouDidMostWithComeFirst() {
        ActivityFeedItem a = plan("Hike", ActivityCategory.HIKING, daysAgo(35));
        ActivityFeedItem b = plan("Climb", ActivityCategory.CLIMBING, daysAgo(120));
        ActivityFeedItem c = plan("Climb 2", ActivityCategory.CLIMBING, daysAgo(150));
        happened(a, b, c);
        together(a, ana);
        together(b, nika);
        together(c, nika);

        assertThat(service.catchUp(me, 5, NOW)).extracting(line -> line.user().userId())
                .containsExactly(nika, ana);
    }

    @Test
    void ofTwoWithTheSameCountTheLongerQuietComesFirst() {
        ActivityFeedItem recent = plan("Hike", ActivityCategory.HIKING, daysAgo(35));
        ActivityFeedItem older = plan("Dinner", ActivityCategory.FOOD_AND_DRINK, daysAgo(100));
        happened(recent, older);
        together(recent, ana);
        together(older, nika);

        assertThat(service.catchUp(me, 5, NOW)).extracting(line -> line.user().userId())
                .containsExactly(nika, ana);
    }

    @Test
    void thePeopleInAPlanWhoAreNotFriendsAreIgnored() {
        ActivityFeedItem old = plan("Party", ActivityCategory.PARTY, daysAgo(60));
        happened(old);
        together(old, UUID.randomUUID());

        assertThat(service.catchUp(me, 5, NOW)).isEmpty();
    }

    @Test
    void theLimitCutsTheList() {
        ActivityFeedItem old = plan("Party", ActivityCategory.PARTY, daysAgo(60));
        happened(old);
        together(old, nika, ana, luka);

        assertThat(service.catchUp(me, 2, NOW)).hasSize(2);
    }

    private void happened(ActivityFeedItem... plans) {
        when(history.happenedBetween(eq(me), eq(me), any(), any())).thenReturn(List.of(plans));
    }

    private void together(ActivityFeedItem plan, UUID... others) {
        togetherAnswers.put(plan.activityId(), List.of(others));
    }

    /** Who else was in each plan, as {@code companions} answers for whichever plans it is asked about. */
    private final Map<UUID, List<UUID>> togetherAnswers = new java.util.HashMap<>();

    private static ZonedDateTime daysAgo(int days) {
        return NOW.minusSeconds(days * 86_400L).atZone(ZoneOffset.UTC);
    }

    private ActivityFeedItem plan(String title, ActivityCategory category, ZonedDateTime start) {
        return new ActivityFeedItem(
                UUID.randomUUID(), me, "me", "Me", title, ActivityType.SPECIFIC_EVENT,
                ActivityVisibility.FRIENDS, start, null, true, null, null, null, 2,
                null, null, null, category, null, null, null, ActivityStatus.ENDED,
                null, null, null, false);
    }
}
