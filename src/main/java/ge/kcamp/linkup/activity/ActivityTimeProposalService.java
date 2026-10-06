package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.command.ActivityCommandHandler;
import ge.kcamp.linkup.activity.command.RescheduleActivityCommand;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.TimeProposal;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.enums.TimeProposalStatus;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import ge.kcamp.linkup.activity.repository.TimeProposalRepository;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Suggest another time": anyone with a place in a plan - invited, going, or having
 * declined - proposes a different start, and the host accepts or declines it.
 * <p>
 * Accepting is the whole point, so it does three things in one transaction: moves the plan
 * for everyone (through {@link ActivityCommandHandler}, so the feed re-scores it and the
 * people in it hear it moved, as for any edit), puts the proposer in the plan, and retires
 * the other open suggestions, which were for a time the plan no longer has.
 * <p>
 * Who may do what mirrors the rest of the activity module. The host-only steps answer a
 * non-host with the same 404 as editing someone else's plan; proposing needs a
 * {@code participants} row, because a stranger who can merely see a public plan has "join"
 * for that and the host owes them no say over its schedule.
 */
@Service
public class ActivityTimeProposalService {

    /** A suggestion must differ from the plan's own time by at least this, or it suggests nothing. */
    private static final Duration MIN_SHIFT = Duration.ofMinutes(1);

    private final ActivityRepository activityRepository;
    private final ParticipantRepository participantRepository;
    private final TimeProposalRepository proposalRepository;
    private final ActivityCommandHandler commandHandler;
    private final UserDirectoryService userDirectoryService;
    private final ApplicationEventPublisher eventPublisher;

    public ActivityTimeProposalService(
            ActivityRepository activityRepository,
            ParticipantRepository participantRepository,
            TimeProposalRepository proposalRepository,
            ActivityCommandHandler commandHandler,
            UserDirectoryService userDirectoryService,
            ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.participantRepository = participantRepository;
        this.proposalRepository = proposalRepository;
        this.commandHandler = commandHandler;
        this.userDirectoryService = userDirectoryService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Records a suggestion and tells the host. Suggesting again replaces the proposer's own
     * open one, so there is never more than one per person to answer.
     */
    @Transactional
    public ActivityTimeProposal propose(
            UUID activityId, UUID proposerId, ZonedDateTime start, ZonedDateTime end, String message) {
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(ActivityNotVisibleException::new);
        if (activity.getCreatorId().equals(proposerId)) {
            throw new IllegalArgumentException("It's your plan - just change the time.");
        }
        if (participantRepository.findByIdActivityIdAndIdUserId(activityId, proposerId).isEmpty()) {
            // Not a participant: indistinguishable from a plan they can't see.
            throw new ActivityNotVisibleException();
        }
        requireOpenForRescheduling(activity);

        ZonedDateTime now = ZonedDateTime.now();
        if (!start.isAfter(now)) {
            throw new IllegalArgumentException("That time has already passed.");
        }
        if (end != null && !end.isAfter(start)) {
            throw new IllegalArgumentException("The end has to be after the start.");
        }
        if (Duration.between(activity.getStartTime(), start).abs().compareTo(MIN_SHIFT) < 0) {
            throw new IllegalArgumentException("That's the time it's already set for.");
        }

        for (TimeProposal open : proposalRepository.findByActivityIdAndProposerIdAndStatus(
                activityId, proposerId, TimeProposalStatus.PENDING)) {
            resolve(open, TimeProposalStatus.WITHDRAWN, now);
        }
        // The replaced row has to reach the database before the new one, or the unique index
        // on open suggestions sees both.
        proposalRepository.flush();

        TimeProposal proposal = new TimeProposal();
        proposal.setActivityId(activityId);
        proposal.setProposerId(proposerId);
        proposal.setProposedStartTime(start);
        proposal.setProposedEndTime(end);
        proposal.setMessage(blankToNull(message));
        proposal.setCreatedAt(now);
        TimeProposal saved = proposalRepository.save(proposal);

        Instant at = now.toInstant();
        eventPublisher.publishEvent(new ActivityTimeProposedEvent(
                activityId, activity.getCreatorId(), proposerId, activity.getTitle(),
                activity.getStartTime(), start, activity.isHasTime(), saved.getMessage(), at));
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, at));
        return view(saved, usernames(List.of(saved)));
    }

    /**
     * Open suggestions: all of them for the host, only your own for anyone else. The same
     * split the {@code time_proposals} select policy makes, applied here as well so the
     * answer doesn't depend on it.
     */
    @Transactional(readOnly = true)
    public List<ActivityTimeProposal> listOpen(UUID activityId, UUID viewerId) {
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(ActivityNotVisibleException::new);
        List<TimeProposal> open = proposalRepository
                .findByActivityIdAndStatusOrderByCreatedAtAsc(activityId, TimeProposalStatus.PENDING);
        if (!activity.getCreatorId().equals(viewerId)) {
            open = open.stream().filter(p -> p.getProposerId().equals(viewerId)).toList();
        }
        Map<UUID, String> names = usernames(open);
        return open.stream().map(p -> view(p, names)).toList();
    }

    /**
     * Host accepts: the plan moves, the proposer is in it, and the other open suggestions
     * are retired. The proposer is told it was accepted, so they are left out of the
     * "plan updated" notice that goes to everyone else.
     */
    @Transactional
    public void accept(UUID activityId, UUID hostId, UUID proposalId) {
        Activity activity = requireHost(activityId, hostId);
        TimeProposal proposal = requireOpen(activityId, proposalId);
        requireOpenForRescheduling(activity);

        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime start = proposal.getProposedStartTime();
        if (!start.isAfter(now)) {
            throw new IllegalArgumentException("That time has already passed.");
        }
        commandHandler.handle(new RescheduleActivityCommand(
                activityId, hostId, start, endFor(activity, proposal), Set.of(proposal.getProposerId())));

        resolve(proposal, TimeProposalStatus.ACCEPTED, now);
        for (TimeProposal other : proposalRepository
                .findByActivityIdAndStatusOrderByCreatedAtAsc(activityId, TimeProposalStatus.PENDING)) {
            resolve(other, TimeProposalStatus.SUPERSEDED, now);
        }
        participantRepository.upsertStatus(activityId, proposal.getProposerId(), ParticipantStatus.JOINED.name());

        Instant at = now.toInstant();
        eventPublisher.publishEvent(new ActivityTimeProposalAnsweredEvent(
                activityId, hostId, proposal.getProposerId(), activity.getTitle(),
                true, start, activity.isHasTime(), at));
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, at));
    }

    /** Host declines; the plan is unchanged and the proposer is told. */
    @Transactional
    public void decline(UUID activityId, UUID hostId, UUID proposalId) {
        Activity activity = requireHost(activityId, hostId);
        TimeProposal proposal = requireOpen(activityId, proposalId);
        ZonedDateTime now = ZonedDateTime.now();
        resolve(proposal, TimeProposalStatus.DECLINED, now);

        Instant at = now.toInstant();
        eventPublisher.publishEvent(new ActivityTimeProposalAnsweredEvent(
                activityId, hostId, proposal.getProposerId(), activity.getTitle(),
                false, proposal.getProposedStartTime(), activity.isHasTime(), at));
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, at));
    }

    /** The proposer takes their suggestion back. Nobody is notified; the host's list just updates. */
    @Transactional
    public void withdraw(UUID activityId, UUID proposerId, UUID proposalId) {
        TimeProposal proposal = proposalRepository.findByIdAndActivityId(proposalId, activityId)
                .filter(found -> found.getProposerId().equals(proposerId))
                .orElseThrow(ActivityNotVisibleException::new);
        if (proposal.getStatus() != TimeProposalStatus.PENDING) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        resolve(proposal, TimeProposalStatus.WITHDRAWN, now);
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, now.toInstant()));
    }

    /**
     * A plan with a length keeps it when the suggestion names only a start, so moving a
     * two-hour dinner an hour later doesn't quietly turn it into one with no end.
     */
    private static ZonedDateTime endFor(Activity activity, TimeProposal proposal) {
        if (proposal.getProposedEndTime() != null) {
            return proposal.getProposedEndTime();
        }
        ZonedDateTime end = activity.getEndTime();
        if (end == null || !end.isAfter(activity.getStartTime())) {
            return null;
        }
        return proposal.getProposedStartTime().plus(Duration.between(activity.getStartTime(), end));
    }

    /**
     * Only a plan that hasn't begun can be moved, and only a one-off: a repeating plan's
     * start is the anchor of its series (V24), so "move it to Friday" has no single answer.
     */
    private static void requireOpenForRescheduling(Activity activity) {
        if (activity.getRepeatFrequency() != null) {
            throw new IllegalArgumentException("A repeating plan can't be moved to a single new time.");
        }
        ActivityStatus status = ActivityStatusResolver.resolve(
                ActivityStatusResolver.Lifecycle.of(activity), ZonedDateTime.now());
        if (status != ActivityStatus.UPCOMING) {
            throw new IllegalArgumentException("This plan has already started or is over.");
        }
    }

    private Activity requireHost(UUID activityId, UUID hostId) {
        return activityRepository.findById(activityId)
                .filter(activity -> activity.getCreatorId().equals(hostId))
                .orElseThrow(ActivityNotVisibleException::new);
    }

    private TimeProposal requireOpen(UUID activityId, UUID proposalId) {
        TimeProposal proposal = proposalRepository.findByIdAndActivityId(proposalId, activityId)
                .orElseThrow(ActivityNotVisibleException::new);
        if (proposal.getStatus() != TimeProposalStatus.PENDING) {
            throw new IllegalArgumentException("That suggestion has already been answered or taken back.");
        }
        return proposal;
    }

    private void resolve(TimeProposal proposal, TimeProposalStatus status, ZonedDateTime at) {
        proposal.setStatus(status);
        proposal.setResolvedAt(at);
        proposalRepository.save(proposal);
    }

    private Map<UUID, String> usernames(List<TimeProposal> proposals) {
        Map<UUID, UserSummary> users = userDirectoryService.findByIds(
                proposals.stream().map(TimeProposal::getProposerId).distinct().toList());
        return users.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().username()));
    }

    private static ActivityTimeProposal view(TimeProposal proposal, Map<UUID, String> names) {
        return new ActivityTimeProposal(
                proposal.getId(), proposal.getActivityId(), proposal.getProposerId(),
                names.get(proposal.getProposerId()), proposal.getProposedStartTime(),
                proposal.getProposedEndTime(), proposal.getMessage(), proposal.getStatus(),
                proposal.getCreatedAt());
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
