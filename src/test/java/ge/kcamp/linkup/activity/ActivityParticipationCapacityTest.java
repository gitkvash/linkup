package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.command.ActivityPersistenceService;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Participant;
import ge.kcamp.linkup.activity.entity.ParticipantId;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.exception.ActivityFullException;
import ge.kcamp.linkup.activity.query.ActivityQueryRepository;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import ge.kcamp.linkup.identity.UserDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A plan's cap on who can be going, and answering "maybe" without taking a place. */
class ActivityParticipationCapacityTest {

    private final UUID activityId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID guest = UUID.randomUUID();

    private Activity activity;
    private ParticipantRepository participants;
    private ActivityParticipationService service;

    @BeforeEach
    void setUp() {
        ActivityRepository activities = mock(ActivityRepository.class);
        participants = mock(ParticipantRepository.class);
        ActivityQueryRepository queries = mock(ActivityQueryRepository.class);
        service = new ActivityParticipationService(
                activities, participants, queries, mock(UserDirectoryService.class),
                mock(ActivityPersistenceService.class), mock(ApplicationEventPublisher.class));

        activity = new Activity();
        activity.setId(activityId);
        activity.setCreatorId(hostId);
        activity.setTitle("Five-a-side");
        when(activities.findById(activityId)).thenReturn(Optional.of(activity));
        when(queries.isVisible(any(), any())).thenReturn(true);
    }

    @Test
    void aPlanWithNoLimitNeverTakesTheLock() {
        activity.setMaxParticipants(null);

        assertThat(service.join(activityId, guest)).isEqualTo(ParticipantStatus.JOINED);

        verify(participants, never()).lockPlan(anyString());
        verify(participants).upsertStatus(activityId, guest, "JOINED");
    }

    @Test
    void joiningWithRoomLeftWorks() {
        activity.setMaxParticipants(4);
        when(participants.countByIdActivityIdAndStatus(activityId, ParticipantStatus.JOINED)).thenReturn(3L);

        assertThat(service.join(activityId, guest)).isEqualTo(ParticipantStatus.JOINED);

        verify(participants).lockPlan(activityId.toString());
        verify(participants).upsertStatus(activityId, guest, "JOINED");
    }

    @Test
    void aFullPlanRefusesJoinAndWritesNothing() {
        activity.setMaxParticipants(4);
        when(participants.countByIdActivityIdAndStatus(activityId, ParticipantStatus.JOINED)).thenReturn(4L);

        assertThatThrownBy(() -> service.join(activityId, guest)).isInstanceOf(ActivityFullException.class);

        verify(participants, never()).upsertStatus(any(), any(), anyString());
    }

    @Test
    void aFullPlanAlsoRefusesAnInviteeAccepting() {
        activity.setMaxParticipants(2);
        when(participants.countByIdActivityIdAndStatus(activityId, ParticipantStatus.JOINED)).thenReturn(2L);

        assertThatThrownBy(() -> service.respond(activityId, guest, true, null))
                .isInstanceOf(ActivityFullException.class);

        verify(participants, never()).upsertStatus(any(), any(), anyString());
    }

    @Test
    void someoneAlreadyGoingCanJoinAgainOnAFullPlan() {
        activity.setMaxParticipants(2);
        when(participants.countByIdActivityIdAndStatus(activityId, ParticipantStatus.JOINED)).thenReturn(2L);
        when(participants.findByIdActivityIdAndIdUserId(activityId, guest)).thenReturn(Optional.of(
                new Participant(new ParticipantId(activityId, guest), activity, ParticipantStatus.JOINED)));

        assertThat(service.join(activityId, guest)).isEqualTo(ParticipantStatus.JOINED);
    }

    @Test
    void aFullPlanStillTakesADeclineAndAMaybe() {
        activity.setMaxParticipants(2);
        when(participants.countByIdActivityIdAndStatus(activityId, ParticipantStatus.JOINED)).thenReturn(2L);

        assertThat(service.respond(activityId, guest, false, null)).isEqualTo(ParticipantStatus.DECLINED);
        assertThat(service.maybe(activityId, guest)).isEqualTo(ParticipantStatus.MAYBE);

        verify(participants, never()).lockPlan(anyString());
    }

    @Test
    void maybeIsStoredAsMaybe() {
        assertThat(service.maybe(activityId, guest)).isEqualTo(ParticipantStatus.MAYBE);

        verify(participants).upsertStatus(activityId, guest, "MAYBE");
    }

    @Test
    void theHostCannotBeMaybe() {
        assertThatThrownBy(() -> service.maybe(activityId, hostId))
                .isInstanceOf(IllegalArgumentException.class);

        verify(participants, never()).upsertStatus(any(), any(), anyString());
    }
}
