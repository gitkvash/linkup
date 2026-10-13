package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.command.ActivityPersistenceService;
import ge.kcamp.linkup.activity.dto.RespondRequest;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Participant;
import ge.kcamp.linkup.activity.entity.ParticipantId;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.query.ActivityQueryRepository;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Saying no to an invitation, with or without a reason, and who gets to read it. */
class ActivityParticipationDeclineReasonTest {

    private final UUID activityId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID guest = UUID.randomUUID();
    private final UUID otherGuest = UUID.randomUUID();

    private ParticipantRepository participants;
    private ApplicationEventPublisher events;
    private ActivityParticipationService service;

    @BeforeEach
    void setUp() {
        ActivityRepository activities = mock(ActivityRepository.class);
        participants = mock(ParticipantRepository.class);
        ActivityQueryRepository queries = mock(ActivityQueryRepository.class);
        UserDirectoryService users = mock(UserDirectoryService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ActivityParticipationService(
                activities, participants, queries, users,
                mock(ActivityPersistenceService.class), events);

        Activity activity = new Activity();
        activity.setId(activityId);
        activity.setCreatorId(hostId);
        activity.setTitle("Dinner");
        when(activities.findById(activityId)).thenReturn(Optional.of(activity));
        // Everyone in the test may see the plan.
        when(queries.isVisible(any(), any())).thenReturn(true);

        Participant declined = new Participant(
                new ParticipantId(activityId, guest), activity, ParticipantStatus.DECLINED);
        declined.setDeclineReason("Work until 7");
        Participant invited = new Participant(
                new ParticipantId(activityId, otherGuest), activity, ParticipantStatus.INVITED);
        when(participants.findByIdActivityIdAndIdUserId(activityId, guest)).thenReturn(Optional.of(declined));
        when(participants.findByIdActivityIdOrderByStatusAsc(activityId))
                .thenReturn(List.of(declined, invited));
        when(users.findByIds(anyCollection())).thenReturn(Map.of(
                guest, new UserSummary(guest, "nino", null, null),
                otherGuest, new UserSummary(otherGuest, "luka", null, null)));
    }

    @Test
    void aDeclineWithAReasonStoresItTrimmed() {
        ParticipantStatus status = service.respond(activityId, guest, false, "  Work until 7  ");

        assertThat(status).isEqualTo(ParticipantStatus.DECLINED);
        verify(participants).upsertDecline(activityId, guest, "Work until 7");
        verify(participants, never()).upsertStatus(any(), any(), anyString());
    }

    @Test
    void aBlankReasonIsTheSameAsNone() {
        service.respond(activityId, guest, false, "   ");

        verify(participants).upsertStatus(activityId, guest, "DECLINED");
        verify(participants, never()).upsertDecline(any(), any(), anyString());
    }

    @Test
    void sayingYesDropsAnyReasonSent() {
        ParticipantStatus status = service.respond(activityId, guest, true, "ignored");

        assertThat(status).isEqualTo(ParticipantStatus.JOINED);
        verify(participants).upsertStatus(activityId, guest, "JOINED");
        verify(participants, never()).upsertDecline(any(), any(), anyString());
    }

    @Test
    void aReasonOverTheLimitIsRefused() {
        String tooLong = "x".repeat(RespondRequest.MAX_REASON + 1);

        assertThatThrownBy(() -> service.respond(activityId, guest, false, tooLong))
                .isInstanceOf(IllegalArgumentException.class);
        verify(participants, never()).upsertDecline(any(), any(), anyString());
    }

    @Test
    void theHostAndTheDeclinerCanReadTheReason() {
        assertThat(reasonSeenBy(hostId)).isEqualTo("Work until 7");
        assertThat(reasonSeenBy(guest)).isEqualTo("Work until 7");
    }

    @Test
    void anotherGuestCannot() {
        assertThat(reasonSeenBy(otherGuest)).isNull();
    }

    private List<ActivityInvitationDeclinedEvent> declinedEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(ActivityInvitationDeclinedEvent.class::isInstance)
                .map(ActivityInvitationDeclinedEvent.class::cast)
                .toList();
    }

    @Test
    void theHostIsToldAboutADeclineWithItsReason() {
        // The stubbed row for otherGuest is INVITED, so this is a first answer.
        service.respond(activityId, otherGuest, false, " Out of town ");

        assertThat(declinedEvents()).singleElement().satisfies(event -> {
            assertThat(event.hostId()).isEqualTo(hostId);
            assertThat(event.declinerId()).isEqualTo(otherGuest);
            assertThat(event.title()).isEqualTo("Dinner");
            assertThat(event.reason()).isEqualTo("Out of town");
        });
    }

    @Test
    void aDeclineWithoutAReasonStillTellsTheHost() {
        service.respond(activityId, otherGuest, false, null);

        assertThat(declinedEvents()).singleElement()
                .satisfies(event -> assertThat(event.reason()).isNull());
    }

    @Test
    void sayingTheSameNoAgainIsNotNewsToTheHost() {
        // guest is already DECLINED with "Work until 7".
        service.respond(activityId, guest, false, "Work until 7");

        assertThat(declinedEvents()).isEmpty();
    }

    @Test
    void aChangedReasonIsNews() {
        service.respond(activityId, guest, false, "Actually, I'm ill");

        assertThat(declinedEvents()).singleElement()
                .satisfies(event -> assertThat(event.reason()).isEqualTo("Actually, I'm ill"));
    }

    @Test
    void acceptingTellsTheHostNothingAboutDeclining() {
        service.respond(activityId, otherGuest, true, null);

        verify(events, never()).publishEvent(any(ActivityInvitationDeclinedEvent.class));
    }

    private String reasonSeenBy(UUID viewer) {
        return service.listParticipants(activityId, viewer).stream()
                .filter(p -> p.userId().equals(guest))
                .findFirst().orElseThrow()
                .declineReason();
    }
}
