package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.dto.CancelRequest;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Starting or cancelling a plan tells the people in it - once per run. */
class ActivityLifecycleServiceTest {

    private final UUID activityId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID guest = UUID.randomUUID();

    private ParticipantRepository participants;
    private ApplicationEventPublisher events;
    private ActivityLifecycleService service;
    private Activity activity;

    @BeforeEach
    void setUp() {
        ActivityRepository activities = mock(ActivityRepository.class);
        participants = mock(ParticipantRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ActivityLifecycleService(activities, participants, events);

        activity = new Activity();
        activity.setId(activityId);
        activity.setCreatorId(hostId);
        activity.setTitle("Run");
        activity.setStartTime(ZonedDateTime.now().plusHours(1));
        when(activities.findById(activityId)).thenReturn(Optional.of(activity));
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(hostId, guest));
    }

    @Test
    void theFirstStartTellsEveryoneButTheHost() {
        service.start(activityId, hostId);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(ActivityStartedEvent.class, event -> {
            assertThat(event.activityId()).isEqualTo(activityId);
            assertThat(event.hostId()).isEqualTo(hostId);
            assertThat(event.participantIds()).containsExactly(guest);
            assertThat(event.occurredAt()).isEqualTo(activity.getStartedAt().toInstant());
        });
    }

    @Test
    void aDoubleTapOnALivePlanTellsNobodyAndKeepsTheFirstStart() {
        ZonedDateTime firstStart = ZonedDateTime.now().minusMinutes(10);
        activity.setStartedAt(firstStart);

        service.start(activityId, hostId);

        assertThat(activity.getStartedAt()).isEqualTo(firstStart);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void startingAPlanTheClockCancelledReopensItAsANewRun() {
        activity.setStartTime(ZonedDateTime.now().minusHours(3));

        service.start(activityId, hostId);

        assertThat(activity.getStartedAt()).isAfter(ZonedDateTime.now().minusMinutes(1));
        verify(events).publishEvent(any(ActivityStartedEvent.class));
    }

    @Test
    void cancellingTellsEveryoneButTheHostOnce() {
        service.cancel(activityId, hostId, null);
        service.cancel(activityId, hostId, null);

        assertThat(activity.getCancelledAt()).isNotNull();
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(ActivityCancelledEvent.class, event -> {
            assertThat(event.activityId()).isEqualTo(activityId);
            assertThat(event.participantIds()).containsExactly(guest);
        });
    }

    @Test
    void theHostsReasonTravelsWithTheCancellationTrimmed() {
        service.cancel(activityId, hostId, "  Venue is closed  ");

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(
                ActivityCancelledEvent.class, event -> assertThat(event.reason()).isEqualTo("Venue is closed"));
    }

    @Test
    void aBlankReasonIsNoReason() {
        service.cancel(activityId, hostId, "   ");

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(
                ActivityCancelledEvent.class, event -> assertThat(event.reason()).isNull());
    }

    @Test
    void aReasonOverTheLimitIsRefusedAndNothingIsCancelled() {
        String tooLong = "x".repeat(CancelRequest.MAX_REASON + 1);

        assertThatThrownBy(() -> service.cancel(activityId, hostId, tooLong))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(activity.getCancelledAt()).isNull();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aPlanThatAlreadyHappenedCannotBeCancelled() {
        activity.setStartedAt(ZonedDateTime.now().minusHours(3));
        activity.setEndedAt(ZonedDateTime.now().minusHours(2));

        assertThatThrownBy(() -> service.cancel(activityId, hostId, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(activity.getCancelledAt()).isNull();
    }

    @Test
    void sayingItHappenedRecordsTheRunItWasPlannedAsAndPushesNothing() {
        ZonedDateTime start = ZonedDateTime.now().minusHours(5);
        activity.setHasTime(true);
        activity.setStartTime(start);

        service.checkIn(activityId, hostId, true);

        assertThat(activity.getStartedAt()).isEqualTo(start);
        assertThat(activity.getEndedAt()).isEqualTo(start.plus(ActivityStatusResolver.DEFAULT_DURATION));
        assertThat(activity.getCancelledAt()).isNull();
        verify(events).publishEvent(any(ActivityChangedEvent.class));
        verify(events, never()).publishEvent(any(ActivityStartedEvent.class));
    }

    @Test
    void sayingItDidNotHappenCancelsItWithoutTellingAnyone() {
        activity.setHasTime(true);
        activity.setStartTime(ZonedDateTime.now().minusHours(3));

        service.checkIn(activityId, hostId, false);

        assertThat(activity.getCancelledAt()).isNotNull();
        assertThat(activity.getStartedAt()).isNull();
        verify(events, never()).publishEvent(any(ActivityCancelledEvent.class));
        verify(events).publishEvent(any(ActivityChangedEvent.class));
    }

    @Test
    void theHostCanStillSayItHappenedAfterTheClockCancelledIt() {
        activity.setHasTime(true);
        activity.setStartTime(ZonedDateTime.now().minusDays(3));

        service.checkIn(activityId, hostId, true);

        assertThat(activity.getStartedAt()).isEqualTo(activity.getStartTime());
        assertThat(activity.getEndedAt()).isNotNull();
    }

    @Test
    void checkingInBeforeTheQuestionIsDueIsRefused() {
        activity.setHasTime(true);
        activity.setStartTime(ZonedDateTime.now().minusMinutes(30));

        assertThatThrownBy(() -> service.checkIn(activityId, hostId, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(activity.getStartedAt()).isNull();
    }

    @Test
    void aPlanAlreadyAnsweredIsLeftAsItIs() {
        ZonedDateTime cancelled = ZonedDateTime.now().minusHours(1);
        activity.setHasTime(true);
        activity.setStartTime(ZonedDateTime.now().minusHours(3));
        activity.setCancelledAt(cancelled);

        service.checkIn(activityId, hostId, true);

        assertThat(activity.getCancelledAt()).isEqualTo(cancelled);
        assertThat(activity.getStartedAt()).isNull();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void onlyTheHostCanCheckIn() {
        activity.setHasTime(true);
        activity.setStartTime(ZonedDateTime.now().minusHours(3));

        assertThatThrownBy(() -> service.checkIn(activityId, guest, true))
                .isInstanceOf(ActivityNotVisibleException.class);
    }

    @Test
    void someoneElsesPlanCannotBeCancelled() {
        assertThatThrownBy(() -> service.cancel(activityId, guest, null))
                .isInstanceOf(ActivityNotVisibleException.class);
    }

    private void guestsJoined(UUID... joined) {
        when(participants.findUserIdsByActivityAndStatusIn(activityId, Set.of(ParticipantStatus.JOINED)))
                .thenReturn(List.of(joined));
    }

    @Test
    void remindingTellsEveryoneWhoJoinedButTheHost() {
        guestsJoined(hostId, guest);

        int reminded = service.remind(activityId, hostId);

        assertThat(reminded).isEqualTo(1);
        assertThat(activity.getRemindedAt()).isNotNull();
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(ActivityRemindedEvent.class, event -> {
            assertThat(event.activityId()).isEqualTo(activityId);
            assertThat(event.hostId()).isEqualTo(hostId);
            assertThat(event.participantIds()).containsExactly(guest);
            assertThat(event.live()).isFalse();
            assertThat(event.startTime()).isEqualTo(activity.getStartTime());
        });
    }

    @Test
    void aSecondReminderInsideTheCooldownIsRefused() {
        guestsJoined(guest);

        service.remind(activityId, hostId);

        assertThatThrownBy(() -> service.remind(activityId, hostId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reminded everyone");
        verify(events, times(1)).publishEvent(any(ActivityRemindedEvent.class));
    }

    @Test
    void aReminderAfterTheCooldownGoesOut() {
        guestsJoined(guest);
        activity.setRemindedAt(ZonedDateTime.now().minus(ActivityLifecycleService.REMIND_COOLDOWN).minusSeconds(5));

        assertThat(service.remind(activityId, hostId)).isEqualTo(1);
    }

    @Test
    void aLivePlanCanBeRemindedAndTheMessageSaysSo() {
        guestsJoined(guest);
        activity.setStartedAt(ZonedDateTime.now().minusMinutes(10));

        service.remind(activityId, hostId);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(
                ActivityRemindedEvent.class, event -> assertThat(event.live()).isTrue());
    }

    @Test
    void aPlanThatIsOverCannotBeReminded() {
        guestsJoined(guest);
        activity.setCancelledAt(ZonedDateTime.now().minusMinutes(5));

        assertThatThrownBy(() -> service.remind(activityId, hostId))
                .isInstanceOf(IllegalArgumentException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void remindingAPlanNobodyJoinedIsRefusedAndDoesNotStartTheCooldown() {
        guestsJoined(hostId);

        assertThatThrownBy(() -> service.remind(activityId, hostId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(activity.getRemindedAt()).isNull();
    }

    @Test
    void someoneElsesPlanCannotBeReminded() {
        guestsJoined(guest);

        assertThatThrownBy(() -> service.remind(activityId, guest))
                .isInstanceOf(ActivityNotVisibleException.class);
    }
}
