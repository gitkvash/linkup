package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.query.ActivityHistoryRepository;
import ge.kcamp.linkup.activity.query.ActivityQueryRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The profile's counts read only lifecycles, and still let the resolver decide what
 * "happened" means - so they agree with the plan lists, which read full rows.
 */
class ActivityHistoryCountsTest {

    private final UUID viewer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final ActivityHistoryRepository history = mock(ActivityHistoryRepository.class);
    private final ActivityQueryRepository queries = mock(ActivityQueryRepository.class);
    private final ActivityHistoryService service = new ActivityHistoryService(history, queries);

    @Test
    void onlyPlansThatEndedCountAsTogether() {
        when(history.findStartedTogetherResolvable(viewer, other)).thenReturn(List.of(
                ended(ActivityCategory.FOOD_AND_DRINK), ended(ActivityCategory.SPORTS),
                cancelled(ActivityCategory.FOOD_AND_DRINK)));

        assertThat(service.countHappenedTogether(viewer, other)).isEqualTo(2);
        verifyNoInteractions(queries);
    }

    @Test
    void favouritesAreCountedByCategoryAmongPlansThatEnded() {
        when(history.findTookPartBetweenResolvable(any(), any(), any(), any())).thenReturn(List.of(
                ended(ActivityCategory.FOOD_AND_DRINK), ended(ActivityCategory.FOOD_AND_DRINK),
                ended(ActivityCategory.SPORTS), cancelled(ActivityCategory.SPORTS)));

        Map<ActivityCategory, Long> counts =
                service.happenedByCategory(other, viewer, Instant.EPOCH, Instant.now());

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                ActivityCategory.FOOD_AND_DRINK, 2L, ActivityCategory.SPORTS, 1L));
        verifyNoInteractions(queries);
    }

    @Test
    void nobodyHasPlansTogetherWithThemselves() {
        assertThat(service.countHappenedTogether(viewer, viewer)).isZero();
        verifyNoInteractions(history);
    }

    private static ActivityHistoryRepository.Resolvable ended(ActivityCategory category) {
        ZonedDateTime start = ZonedDateTime.now().minusDays(3);
        return new ActivityHistoryRepository.Resolvable(category, new ActivityStatusResolver.Lifecycle(
                start, null, true, null, null, null, start, start.plusHours(1), null));
    }

    private static ActivityHistoryRepository.Resolvable cancelled(ActivityCategory category) {
        ZonedDateTime start = ZonedDateTime.now().minusDays(3);
        return new ActivityHistoryRepository.Resolvable(category, new ActivityStatusResolver.Lifecycle(
                start, null, true, null, null, null, null, null, start.minusHours(1)));
    }
}
