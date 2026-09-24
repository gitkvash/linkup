package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.activity.ActivityCancelledEvent;
import ge.kcamp.linkup.activity.ActivityDeletedEvent;
import ge.kcamp.linkup.activity.ActivityUpdatedEvent;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.LocationRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import ge.kcamp.linkup.nlp.NlpParserService;
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
                activities, locations, mock(NlpParserService.class), persistence, participants, events);

        activity = new Activity();
        activity.setId(activityId);
        activity.setCreatorId(creatorId);
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
        ZonedDateTime start = ZonedDateTime.parse("2026-10-01T18:00:00+04:00[Asia/Tbilisi]");
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
    void aRefusedDeletePublishesNothing() {
        assertThatThrownBy(() -> handler.handle(new DeleteActivityCommand(activityId, UUID.randomUUID())))
                .isInstanceOf(ActivityNotVisibleException.class);

        verify(events, never()).publishEvent(any(Object.class));
    }
}
