package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.activity.ActivityCancelledEvent;
import ge.kcamp.linkup.activity.ActivityDeletedEvent;
import ge.kcamp.linkup.activity.ActivityEditedEvent;
import ge.kcamp.linkup.activity.ActivityUpdatedEvent;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.LocationRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Edits and deletes tell the feed, which otherwise keeps stale timeline entries. */
class ActivityCommandHandlerEventsTest {

    private final UUID activityId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    private ActivityRepository activities;
    private ActivityPersistenceService persistence;
    private ParticipantRepository participants;
    private ApplicationEventPublisher events;
    private ActivityCommandHandler handler;
    private Activity activity;

    @BeforeEach
    void setUp() {
        activities = mock(ActivityRepository.class);
        LocationRepository locations = mock(LocationRepository.class);
        persistence = mock(ActivityPersistenceService.class);
        participants = mock(ParticipantRepository.class);
        events = mock(ApplicationEventPublisher.class);
        handler = new ActivityCommandHandler(
                activities, locations, persistence, participants, events);

        activity = new Activity();
        activity.setId(activityId);
        activity.setCreatorId(creatorId);
        activity.setStartTime(ZonedDateTime.now().plusDays(1));
        when(activities.findById(activityId)).thenReturn(Optional.of(activity));
        when(activities.save(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(locations.findByActivityId(activityId)).thenReturn(Optional.empty());
    }

    @Test
    void anEditPublishesTheNewStartTimeAndAudienceGroup() {
        ZonedDateTime newStart = ZonedDateTime.parse("2026-10-01T18:00:00+04:00[Asia/Tbilisi]");
        when(persistence.resolveGroupId(creatorId, ActivityVisibility.GROUP, groupId)).thenReturn(groupId);

        handler.handle(new UpdateActivityCommand(activityId, creatorId, "Dinner", newStart, null, true,
                null, null, null, ActivityVisibility.GROUP, groupId, null, null, null, null));

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(ActivityUpdatedEvent.class, event -> {
            assertThat(event.activityId()).isEqualTo(activityId);
            assertThat(event.creatorId()).isEqualTo(creatorId);
            assertThat(event.startTime()).isEqualTo(newStart);
            assertThat(event.groupId()).isEqualTo(groupId);
            assertThat(event.occurredAt()).isNotNull();
        });
    }

    @Test
    void aDeletePublishesActivityDeleted() {
        handler.handle(new DeleteActivityCommand(activityId, creatorId));

        verify(activities).delete(activity);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(ActivityDeletedEvent.class, event -> {
            assertThat(event.activityId()).isEqualTo(activityId);
            assertThat(event.creatorId()).isEqualTo(creatorId);
        });
    }

    @Test
    void aDeleteTellsEveryoneStillInThePlanButTheHost() {
        UUID joined = UUID.randomUUID();
        UUID invited = UUID.randomUUID();
        // Relative, not a date: a plan whose start is over two hours gone reads as
        // cancelled already, and deleting that one tells nobody (below).
        ZonedDateTime start = ZonedDateTime.now().plusDays(5);
        activity.setTitle("Dinner");
        activity.setStartTime(start);
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(creatorId, joined, invited));

        handler.handle(new DeleteActivityCommand(activityId, creatorId));

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues().get(0)).isInstanceOf(ActivityDeletedEvent.class);
        assertThat(published.getAllValues().get(1)).isInstanceOfSatisfying(ActivityCancelledEvent.class, event -> {
            assertThat(event.activityId()).isEqualTo(activityId);
            assertThat(event.hostId()).isEqualTo(creatorId);
            assertThat(event.title()).isEqualTo("Dinner");
            assertThat(event.startTime()).isEqualTo(start);
            assertThat(event.participantIds()).containsExactly(joined, invited);
        });
    }

    @Test
    void deletingAPlanThatWasAlreadyCancelledDoesNotTellAnyoneTwice() {
        activity.setCancelledAt(ZonedDateTime.now().minusMinutes(5));
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(creatorId, UUID.randomUUID()));

        handler.handle(new DeleteActivityCommand(activityId, creatorId));

        verify(events).publishEvent(any(ActivityDeletedEvent.class));
        verify(events, never()).publishEvent(any(ActivityCancelledEvent.class));
    }

    private void edit(String title, ZonedDateTime start, String address) {
        when(persistence.resolveGroupId(creatorId, ActivityVisibility.FRIENDS, null)).thenReturn(null);
        handler.handle(new UpdateActivityCommand(activityId, creatorId, title, start, null, true,
                null, null, address, ActivityVisibility.FRIENDS, null, null, null, null, null));
    }

    private ActivityEditedEvent editedEvent() {
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(published.capture());
        return (ActivityEditedEvent) published.getAllValues().get(1);
    }

    @Test
    void movingAPlanTellsEveryoneInItButTheHost() {
        UUID guest = UUID.randomUUID();
        activity.setTitle("Dinner");
        activity.setHasTime(true);
        ZonedDateTime moved = activity.getStartTime().plusHours(3);
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(creatorId, guest));

        edit("Dinner", moved, null);

        ActivityEditedEvent event = editedEvent();
        assertThat(event.participantIds()).containsExactly(guest);
        assertThat(event.scheduleChanged()).isTrue();
        assertThat(event.placeChanged()).isFalse();
        assertThat(event.previousTitle()).isNull();
        assertThat(event.startTime()).isEqualTo(moved);
    }

    @Test
    void renamingAndChangingThePlaceIsReportedAsSuch() {
        activity.setTitle("Dinner");
        activity.setHasTime(true);
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(UUID.randomUUID()));

        edit("Late dinner", activity.getStartTime(), "Cafe Leila");

        ActivityEditedEvent event = editedEvent();
        assertThat(event.previousTitle()).isEqualTo("Dinner");
        assertThat(event.title()).isEqualTo("Late dinner");
        assertThat(event.scheduleChanged()).isFalse();
        assertThat(event.placeChanged()).isTrue();
        assertThat(event.addressText()).isEqualTo("Cafe Leila");
    }

    @Test
    void anEditThatChangesNothingTheyCareAboutTellsNobody() {
        activity.setTitle("Dinner");
        activity.setHasTime(true);
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(UUID.randomUUID()));

        // Same name, the same moment in another offset, still no place.
        edit("Dinner", activity.getStartTime().withZoneSameInstant(java.time.ZoneOffset.UTC), "  ");

        verify(events, times(1)).publishEvent(any(Object.class));
        verify(events, never()).publishEvent(any(ActivityEditedEvent.class));
    }

    @Test
    void editingAPlanNobodyIsInTellsNobody() {
        activity.setTitle("Dinner");
        activity.setHasTime(true);
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(creatorId));

        edit("Dinner", activity.getStartTime().plusHours(1), null);

        verify(events, never()).publishEvent(any(ActivityEditedEvent.class));
    }

    @Test
    void editingAPlanThatIsOverTellsNobody() {
        activity.setTitle("Dinner");
        activity.setHasTime(true);
        activity.setCancelledAt(ZonedDateTime.now().minusMinutes(5));
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(UUID.randomUUID()));

        edit("Renamed", activity.getStartTime(), null);

        verify(events, never()).publishEvent(any(ActivityEditedEvent.class));
    }

    @Test
    void aRefusedDeletePublishesNothing() {
        assertThatThrownBy(() -> handler.handle(new DeleteActivityCommand(activityId, UUID.randomUUID())))
                .isInstanceOf(ActivityNotVisibleException.class);

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void reschedulingMovesOnlyTheTimeAndTellsEveryoneButTheHostAndTheExcluded() {
        UUID guest = UUID.randomUUID();
        UUID proposer = UUID.randomUUID();
        activity.setTitle("Dinner");
        activity.setHasTime(true);
        ZonedDateTime moved = activity.getStartTime().plusHours(3);
        when(participants.findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN))
                .thenReturn(List.of(creatorId, guest, proposer));

        handler.handle(new RescheduleActivityCommand(activityId, creatorId, moved, null, java.util.Set.of(proposer)));

        assertThat(activity.getStartTime()).isEqualTo(moved);
        assertThat(activity.getTitle()).isEqualTo("Dinner");
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues().get(0)).isInstanceOf(ActivityUpdatedEvent.class);
        assertThat(published.getAllValues().get(1)).isInstanceOfSatisfying(ActivityEditedEvent.class, event -> {
            assertThat(event.participantIds()).containsExactly(guest);
            assertThat(event.scheduleChanged()).isTrue();
            assertThat(event.startTime()).isEqualTo(moved);
        });
    }

    @Test
    void onlyTheHostCanReschedule() {
        assertThatThrownBy(() -> handler.handle(new RescheduleActivityCommand(
                activityId, UUID.randomUUID(), activity.getStartTime().plusHours(1), null, java.util.Set.of())))
                .isInstanceOf(ActivityNotVisibleException.class);
    }

    @Test
    void aRepeatingPlanCannotBeMovedToOneNewTime() {
        activity.setRepeatFrequency(ge.kcamp.linkup.activity.enums.RepeatFrequency.WEEKLY);
        activity.setRepeatInterval(1);

        assertThatThrownBy(() -> handler.handle(new RescheduleActivityCommand(
                activityId, creatorId, activity.getStartTime().plusHours(1), null, java.util.Set.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
