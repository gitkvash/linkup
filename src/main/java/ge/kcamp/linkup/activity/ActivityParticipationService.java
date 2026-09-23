package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.command.ActivityPersistenceService;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Participant;
import ge.kcamp.linkup.activity.entity.ParticipantId;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.query.ActivityQueryRepository;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Joining, leaving, and answering an invitation.
 * <p>
 * Every write here first checks the activity is visible to the caller through the
 * same predicate the read side uses - otherwise "join" would be a way to attach
 * yourself to a private plan you were never shown.
 */
@Service
public class ActivityParticipationService {

    private final ActivityRepository activityRepository;
    private final ParticipantRepository participantRepository;
    private final ActivityQueryRepository activityQueryRepository;
    private final UserDirectoryService userDirectoryService;
    private final ActivityPersistenceService activityPersistenceService;
    private final ApplicationEventPublisher eventPublisher;

    public ActivityParticipationService(
            ActivityRepository activityRepository,
            ParticipantRepository participantRepository,
            ActivityQueryRepository activityQueryRepository,
            UserDirectoryService userDirectoryService,
            ActivityPersistenceService activityPersistenceService,
            ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.participantRepository = participantRepository;
        this.activityQueryRepository = activityQueryRepository;
        this.userDirectoryService = userDirectoryService;
        this.activityPersistenceService = activityPersistenceService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Marks the caller as going. Idempotent: joining twice, or joining something you
     * were invited to, both end as JOINED.
     */
    @Transactional
    public ParticipantStatus join(UUID activityId, UUID userId) {
        requireVisible(activityId, userId);
        return upsert(activityId, userId, ParticipantStatus.JOINED);
    }

    /**
     * Withdraws the caller. The row is deleted rather than set to DECLINED so that
     * re-joining later is clean - except for the creator, who cannot leave their own
     * plan (there would be nobody to own it).
     */
    @Transactional
    public void leave(UUID activityId, UUID userId) {
        Activity activity = requireVisible(activityId, userId);
        if (activity.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException(
                    "You created this plan, so you can't leave it. Cancel it instead.");
        }
        participantRepository
                .findByIdActivityIdAndIdUserId(activityId, userId)
                .ifPresent(participantRepository::delete);
    }

    /** Accept or decline an invitation. */
    @Transactional
    public ParticipantStatus respond(UUID activityId, UUID userId, boolean going) {
        requireVisible(activityId, userId);
        return upsert(
                activityId, userId, going ? ParticipantStatus.JOINED : ParticipantStatus.DECLINED);
    }

    /**
     * Invites more of the host's friends to a plan that already exists, and answers with
     * the updated participant list.
     * <p>
     * Host only - the same rule the {@code participants} insert policy enforces - and a
     * non-host gets the 404 editing gives. Anyone who already has a row (invited, going,
     * or declined) is skipped rather than reset: re-inviting someone who said no would
     * turn their answer back into a question, and notify them about it.
     */
    @Transactional
    public List<ActivityParticipant> invite(UUID activityId, UUID inviterId, List<UUID> userIds) {
        Activity activity = activityRepository.findById(activityId)
                .filter(found -> found.getCreatorId().equals(inviterId))
                .orElseThrow(ActivityNotVisibleException::new);

        activityPersistenceService.requireFriends(inviterId, userIds);

        Set<UUID> existing = participantRepository.findByIdActivityIdOrderByStatusAsc(activityId)
                .stream()
                .map(participant -> participant.getId().getUserId())
                .collect(Collectors.toSet());

        List<UUID> added = userIds.stream()
                .distinct()
                .filter(id -> !id.equals(inviterId))
                .filter(id -> !existing.contains(id))
                .toList();

        for (UUID inviteeId : added) {
            participantRepository.save(Participant.builder()
                    .id(new ParticipantId(activityId, inviteeId))
                    .activity(activity)
                    .status(ParticipantStatus.INVITED)
                    .build());
        }

        if (!added.isEmpty()) {
            eventPublisher.publishEvent(new ActivityInvitationsSentEvent(
                    activityId, inviterId, activity.getTitle(), activity.getStartTime(),
                    added, Instant.now()));
        }

        return listParticipants(activityId, inviterId);
    }

    /** Who's involved, with their names, for the detail screen. */
    @Transactional(readOnly = true)
    public List<ActivityParticipant> listParticipants(UUID activityId, UUID viewerId) {
        requireVisible(activityId, viewerId);

        List<Participant> participants =
                participantRepository.findByIdActivityIdOrderByStatusAsc(activityId);
        Map<UUID, UserSummary> users = userDirectoryService.findByIds(
                participants.stream().map(p -> p.getId().getUserId()).toList());

        return participants.stream()
                .map(participant -> {
                    UUID participantUserId = participant.getId().getUserId();
                    UserSummary summary = users.get(participantUserId);
                    return new ActivityParticipant(
                            participantUserId,
                            summary == null ? null : summary.username(),
                            participant.getStatus());
                })
                .toList();
    }

    /** Activities the caller has been invited to and hasn't answered. */
    @Transactional(readOnly = true)
    public List<ActivityFeedItem> pendingInvitations(UUID userId) {
        List<UUID> activityIds = participantRepository.findActivityIdsByUserAndStatus(
                userId, ParticipantStatus.INVITED);
        return activityQueryRepository.findByIds(activityIds, userId);
    }

    /**
     * Activities the caller has joined but did not create - the counterpart to
     * {@code ActivityQueryService.findByCreator}, which only ever returns activities the
     * caller made themselves.
     */
    @Transactional(readOnly = true)
    public List<ActivityFeedItem> joinedActivities(UUID userId) {
        List<UUID> activityIds = participantRepository.findActivityIdsByUserAndStatus(
                userId, ParticipantStatus.JOINED);
        return activityQueryRepository.findByIds(activityIds, userId).stream()
                .filter(item -> !item.creatorId().equals(userId))
                .toList();
    }

    /**
     * One statement, so two simultaneous joins settle on the same answer instead of one
     * of them losing a primary-key race - see {@code ParticipantRepository.upsertStatus}.
     */
    private ParticipantStatus upsert(UUID activityId, UUID userId, ParticipantStatus status) {
        participantRepository.upsertStatus(activityId, userId, status.name());
        return status;
    }

    /**
     * Loads the activity only if the caller may see it. The visibility check runs
     * through the query repository so there is exactly one definition of "visible".
     */
    private Activity requireVisible(UUID activityId, UUID userId) {
        if (activityQueryRepository.findById(activityId, userId).isEmpty()) {
            throw new ActivityNotVisibleException();
        }
        return activityRepository.findById(activityId)
                .orElseThrow(ActivityNotVisibleException::new);
    }
}
