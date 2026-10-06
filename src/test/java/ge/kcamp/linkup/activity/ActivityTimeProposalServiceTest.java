package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.command.ActivityCommandHandler;
import ge.kcamp.linkup.activity.command.RescheduleActivityCommand;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Participant;
import ge.kcamp.linkup.activity.entity.ParticipantId;
import ge.kcamp.linkup.activity.entity.TimeProposal;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;
import ge.kcamp.linkup.activity.enums.TimeProposalStatus;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import ge.kcamp.linkup.activity.repository.TimeProposalRepository;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Suggesting another time, and the host's answer to it. */
class ActivityTimeProposalServiceTest {

    private final UUID activityId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID guest = UUID.randomUUID();

    private ParticipantRepository participants;
    private TimeProposalRepository proposals;
    private ActivityCommandHandler commands;
    private ApplicationEventPublisher events;
    private ActivityTimeProposalService service;
    private Activity activity;
    private ZonedDateTime planStart;

    @BeforeEach
    void setUp() {
        ActivityRepository activities = mock(ActivityRepository.class);
        participants = mock(ParticipantRepository.class);
        proposals = mock(TimeProposalRepository.class);
        commands = mock(ActivityCommandHandler.class);
        events = mock(ApplicationEventPublisher.class);
        UserDirectoryService users = mock(UserDirectoryService.class);
        service = new ActivityTimeProposalService(
                activities, participants, proposals, commands, users, events);

        planStart = ZonedDateTime.now().plusDays(2).withNano(0);
        activity = new Activity();
        activity.setId(activityId);
        activity.setCreatorId(hostId);
        activity.setTitle("Dinner");
        activity.setStartTime(planStart);
        when(activities.findById(activityId)).thenReturn(Optional.of(activity));
        when(participants.findByIdActivityIdAndIdUserId(activityId, guest)).thenReturn(Optional.of(
                new Participant(new ParticipantId(activityId, guest), activity, ParticipantStatus.INVITED)));
        when(proposals.save(any(TimeProposal.class))).thenAnswer(call -> {
            TimeProposal saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        when(users.findByIds(anyCollection())).thenReturn(
                Map.of(guest, new UserSummary(guest, "nino", null, null)));
    }

    private TimeProposal open(ZonedDateTime start) {
        TimeProposal proposal = new TimeProposal();
        proposal.setId(UUID.randomUUID());
        proposal.setActivityId(activityId);
        proposal.setProposerId(guest);
        proposal.setProposedStartTime(start);
        when(proposals.findByIdAndActivityId(proposal.getId(), activityId)).thenReturn(Optional.of(proposal));
        return proposal;
    }

    @Test
    void aGuestSuggestionIsRecordedAndTheHostIsTold() {
        ZonedDateTime later = planStart.plusHours(3);

        ActivityTimeProposal made = service.propose(activityId, guest, later, null, "  work till 7  ");

        assertThat(made.status()).isEqualTo(TimeProposalStatus.PENDING);
        assertThat(made.message()).isEqualTo("work till 7");
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues().get(0)).isInstanceOfSatisfying(ActivityTimeProposedEvent.class, event -> {
            assertThat(event.hostId()).isEqualTo(hostId);
            assertThat(event.proposerId()).isEqualTo(guest);
            assertThat(event.currentStart()).isEqualTo(planStart);
            assertThat(event.proposedStart()).isEqualTo(later);
        });
    }

    @Test
    void suggestingAgainWithdrawsTheOpenOne() {
        TimeProposal earlier = open(planStart.plusHours(1));
        when(proposals.findByActivityIdAndProposerIdAndStatus(activityId, guest, TimeProposalStatus.PENDING))
                .thenReturn(List.of(earlier));

        service.propose(activityId, guest, planStart.plusHours(2), null, null);

        assertThat(earlier.getStatus()).isEqualTo(TimeProposalStatus.WITHDRAWN);
        assertThat(earlier.getResolvedAt()).isNotNull();
    }

    @Test
    void theHostCannotSuggestATimeForTheirOwnPlan() {
        assertThatThrownBy(() -> service.propose(activityId, hostId, planStart.plusHours(1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void someoneWithNoPlaceInThePlanGetsTheNotFoundAnAbsentPlanGives() {
        assertThatThrownBy(() -> service.propose(
                activityId, UUID.randomUUID(), planStart.plusHours(1), null, null))
                .isInstanceOf(ActivityNotVisibleException.class);
    }

    @Test
    void aTimeThatHasPassedOrIsTheSameAsNowIsRefused() {
        assertThatThrownBy(() -> service.propose(activityId, guest, ZonedDateTime.now().minusHours(1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.propose(activityId, guest, planStart.plusSeconds(20), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(proposals, never()).save(any());
    }

    @Test
    void anEndBeforeTheStartIsRefused() {
        ZonedDateTime start = planStart.plusHours(2);

        assertThatThrownBy(() -> service.propose(activityId, guest, start, start.minusMinutes(5), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPlanThatRepeatsOrHasBegunTakesNoSuggestions() {
        activity.setRepeatFrequency(RepeatFrequency.WEEKLY);
        assertThatThrownBy(() -> service.propose(activityId, guest, planStart.plusHours(1), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        activity.setRepeatFrequency(null);
        activity.setStartedAt(ZonedDateTime.now().minusMinutes(5));
        assertThatThrownBy(() -> service.propose(activityId, guest, planStart.plusHours(1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptingMovesThePlanPutsTheProposerInAndRetiresTheRest() {
        ZonedDateTime later = planStart.plusHours(3);
        TimeProposal chosen = open(later);
        TimeProposal other = open(planStart.plusDays(1));
        other.setProposerId(UUID.randomUUID());
        when(proposals.findByActivityIdAndStatusOrderByCreatedAtAsc(activityId, TimeProposalStatus.PENDING))
                .thenReturn(List.of(other));

        service.accept(activityId, hostId, chosen.getId());

        ArgumentCaptor<RescheduleActivityCommand> moved = ArgumentCaptor.forClass(RescheduleActivityCommand.class);
        verify(commands).handle(moved.capture());
        assertThat(moved.getValue().startTime()).isEqualTo(later);
        assertThat(moved.getValue().notTold()).containsExactly(guest);
        verify(participants).upsertStatus(activityId, guest, "JOINED");
        assertThat(chosen.getStatus()).isEqualTo(TimeProposalStatus.ACCEPTED);
        assertThat(other.getStatus()).isEqualTo(TimeProposalStatus.SUPERSEDED);
        verify(events).publishEvent(org.mockito.ArgumentMatchers.<Object>argThat(event ->
                event instanceof ActivityTimeProposalAnsweredEvent answered
                        && answered.accepted() && answered.proposerId().equals(guest)));
    }

    @Test
    void aPlanWithALengthKeepsItWhenMovedToAnotherStart() {
        activity.setEndTime(planStart.plusHours(2));
        TimeProposal chosen = open(planStart.plusHours(5));

        service.accept(activityId, hostId, chosen.getId());

        ArgumentCaptor<RescheduleActivityCommand> moved = ArgumentCaptor.forClass(RescheduleActivityCommand.class);
        verify(commands).handle(moved.capture());
        assertThat(Duration.between(moved.getValue().startTime(), moved.getValue().endTime()))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void onlyTheHostCanAccept() {
        TimeProposal chosen = open(planStart.plusHours(3));

        assertThatThrownBy(() -> service.accept(activityId, guest, chosen.getId()))
                .isInstanceOf(ActivityNotVisibleException.class);
        verify(commands, never()).handle(any(RescheduleActivityCommand.class));
    }

    @Test
    void aSuggestionAlreadyAnsweredCannotBeAcceptedAgain() {
        TimeProposal chosen = open(planStart.plusHours(3));
        chosen.setStatus(TimeProposalStatus.DECLINED);

        assertThatThrownBy(() -> service.accept(activityId, hostId, chosen.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decliningLeavesThePlanAloneAndTellsTheProposer() {
        TimeProposal chosen = open(planStart.plusHours(3));

        service.decline(activityId, hostId, chosen.getId());

        assertThat(chosen.getStatus()).isEqualTo(TimeProposalStatus.DECLINED);
        verify(commands, never()).handle(any(RescheduleActivityCommand.class));
        verify(participants, never()).upsertStatus(any(), any(), any());
        verify(events).publishEvent(org.mockito.ArgumentMatchers.<Object>argThat(event ->
                event instanceof ActivityTimeProposalAnsweredEvent answered
                        && !answered.accepted() && answered.proposerId().equals(guest)));
    }

    @Test
    void onlyTheProposerCanWithdrawAndNobodyElseIsToldAboutIt() {
        TimeProposal mine = open(planStart.plusHours(3));

        assertThatThrownBy(() -> service.withdraw(activityId, hostId, mine.getId()))
                .isInstanceOf(ActivityNotVisibleException.class);

        service.withdraw(activityId, guest, mine.getId());
        assertThat(mine.getStatus()).isEqualTo(TimeProposalStatus.WITHDRAWN);
    }

    @Test
    void theHostSeesEverySuggestionAndAGuestOnlyTheirOwn() {
        TimeProposal mine = open(planStart.plusHours(1));
        TimeProposal theirs = open(planStart.plusHours(2));
        theirs.setProposerId(UUID.randomUUID());
        when(proposals.findByActivityIdAndStatusOrderByCreatedAtAsc(activityId, TimeProposalStatus.PENDING))
                .thenReturn(List.of(mine, theirs));

        assertThat(service.listOpen(activityId, hostId)).hasSize(2);
        assertThat(service.listOpen(activityId, guest))
                .extracting(ActivityTimeProposal::proposerId).containsExactly(guest);
    }
}
