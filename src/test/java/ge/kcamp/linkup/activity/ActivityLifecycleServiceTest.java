package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Starting a plan tells the people in it - once. */
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
    void startingAgainOrReopeningTellsNobody() {
        activity.setStartedAt(ZonedDateTime.now().minusMinutes(10));
        activity.setEndedAt(ZonedDateTime.now().minusMinutes(1));

        service.start(activityId, hostId);

        verify(events, never()).publishEvent(any(Object.class));
    }
}
