package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.ActivityStatusResolver.Lifecycle;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reminders go out once per occurrence, about half an hour ahead, to the people going. */
class ActivityReminderSchedulerTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-01T14:00:00Z");
    private static final Duration LEAD = Duration.ofMinutes(30);

    private final UUID activityId = UUID.randomUUID();
    private final UUID going = UUID.randomUUID();

    private ActivityRepository activities;
    private ParticipantRepository participants;
    private ApplicationEventPublisher events;
    private MutableClock clock;
    private ActivityReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        activities = mock(ActivityRepository.class);
        participants = mock(ParticipantRepository.class);
        events = mock(ApplicationEventPublisher.class);
        clock = new MutableClock(NOW.toInstant());
        scheduler = new ActivityReminderScheduler(
                activities, participants, events,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Runnable::run, LEAD, Duration.ofMinutes(1), clock);
        when(participants.findUserIdsByActivityAndStatusIn(activityId, Set.of(ParticipantStatus.JOINED)))
                .thenReturn(List.of(going));
    }

    @Test
    void aPlanStartingWithinTheLeadIsRemindedWithItsOwnStartAsTheEventTime() {
        ZonedDateTime start = NOW.plusMinutes(30);
        when(activities.findReminderCandidates(NOW, NOW.plus(LEAD))).thenReturn(List.of(plan(start, null)));

        scheduler.scan();

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(ActivityStartingSoonEvent.class, event -> {
            assertThat(event.activityId()).isEqualTo(activityId);
            assertThat(event.startTime()).isEqualTo(start);
            assertThat(event.participantIds()).containsExactly(going);
            assertThat(event.occurredAt()).isEqualTo(start.toInstant());
        });
    }

    @Test
    void theNextScanOnlyCoversTheTimeThePreviousOneDidNot() {
        when(activities.findReminderCandidates(any(), any())).thenReturn(List.of());
        scheduler.scan();

        clock.advance(Duration.ofMinutes(1));
        scheduler.scan();

        verify(activities).findReminderCandidates(NOW.plus(LEAD), NOW.plus(LEAD).plusMinutes(1));
    }

    @Test
    void aFailedScanLeavesItsWindowForTheNextOne() {
        when(activities.findReminderCandidates(any(), any()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of());
        scheduler.scan();

        clock.advance(Duration.ofMinutes(1));
        scheduler.scan();

        verify(activities).findReminderCandidates(NOW.plusMinutes(1), NOW.plusMinutes(1).plus(LEAD));
    }

    @Test
    void aPlanNobodyJoinedIsNotReminded() {
        when(participants.findUserIdsByActivityAndStatusIn(activityId, Set.of(ParticipantStatus.JOINED)))
                .thenReturn(List.of());
        when(activities.findReminderCandidates(any(), any())).thenReturn(List.of(plan(NOW.plusMinutes(20), null)));

        scheduler.scan();

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aWeeklyPlanIsRemindedForThisWeeksOccurrence() {
        ZonedDateTime firstWeek = NOW.minusWeeks(3).plusMinutes(25);
        when(activities.findReminderCandidates(any(), any()))
                .thenReturn(List.of(plan(firstWeek, RepeatFrequency.WEEKLY)));

        scheduler.scan();

        ArgumentCaptor<ActivityStartingSoonEvent> published = ArgumentCaptor.forClass(ActivityStartingSoonEvent.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue().startTime()).isEqualTo(NOW.plusMinutes(25));
    }

    @Test
    void nextStartAfterWalksARepeatRuleAndStopsWhereItEnds() {
        ZonedDateTime first = ZonedDateTime.parse("2026-09-01T18:00:00Z");
        Lifecycle daily = new Lifecycle(first, null, true, RepeatFrequency.DAILY, 2, first.plusDays(4),
                null, null);

        assertThat(ActivityStatusResolver.nextStartAfter(daily, first.minusMinutes(1))).isEqualTo(first);
        assertThat(ActivityStatusResolver.nextStartAfter(daily, first)).isEqualTo(first.plusDays(2));
        assertThat(ActivityStatusResolver.nextStartAfter(daily, first.plusDays(3))).isEqualTo(first.plusDays(4));
        assertThat(ActivityStatusResolver.nextStartAfter(daily, first.plusDays(4))).isNull();
    }

    @Test
    void nextStartAfterIsNullForAOneOffThatHasStarted() {
        ZonedDateTime start = ZonedDateTime.parse("2026-09-01T18:00:00Z");
        Lifecycle once = new Lifecycle(start, null, true, null, null, null, null, null);

        assertThat(ActivityStatusResolver.nextStartAfter(once, start.minusSeconds(1))).isEqualTo(start);
        assertThat(ActivityStatusResolver.nextStartAfter(once, start)).isNull();
    }

    private Activity plan(ZonedDateTime start, RepeatFrequency repeat) {
        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setTitle("Run");
        activity.setStartTime(start);
        activity.setHasTime(true);
        activity.setRepeatFrequency(repeat);
        return activity;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
