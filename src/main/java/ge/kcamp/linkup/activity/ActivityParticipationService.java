package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.command.ActivityPersistenceService;
import ge.kcamp.linkup.activity.dto.RespondRequest;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Participant;
import ge.kcamp.linkup.activity.entity.ParticipantId;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.exception.ActivityFullException;
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
import java.util.HashSet;
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
     * were invited to, both end as JOINED. A full plan refuses anyone not already in it
     * with {@link ActivityFullException}.
     */
    @Transactional
    public ParticipantStatus join(UUID activityId, UUID userId) {
        Activity activity = requireVisible(activityId, userId);
        requireRoom(activity, userId);
        ParticipantStatus status = upsert(activityId, userId, ParticipantStatus.JOINED);
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, Instant.now()));
        return status;
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
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, Instant.now()));
    }

    /**
     * Accept or decline an invitation. A decline may carry a short {@code reason} for the
     * host; blank counts as none, and it is ignored on accept (which clears any earlier one).
     * <p>
     * The host is told about a decline, but not about the same one twice: answering "no" again
     * with nothing new to say is not news, while a changed reason is.
     */
    @Transactional
    public ParticipantStatus respond(UUID activityId, UUID userId, boolean going, String reason) {
        Activity activity = requireVisible(activityId, userId);
        String trimmed = going || reason == null ? "" : reason.strip();
        if (trimmed.length() > RespondRequest.MAX_REASON) {
            throw new IllegalArgumentException(
                    "Keep the reason under " + RespondRequest.MAX_REASON + " characters.");
        }
        String previousReason = null;
        boolean alreadyDeclined = false;
        if (!going) {
            Participant before = participantRepository
                    .findByIdActivityIdAndIdUserId(activityId, userId).orElse(null);
            alreadyDeclined = before != null && before.getStatus() == ParticipantStatus.DECLINED;
            previousReason = before == null ? null : before.getDeclineReason();
        }

        if (going) {
            requireRoom(activity, userId);
        }

        ParticipantStatus status;
        if (going || trimmed.isEmpty()) {
            status = upsert(
                    activityId, userId, going ? ParticipantStatus.JOINED : ParticipantStatus.DECLINED);
        } else {
            participantRepository.upsertDecline(activityId, userId, trimmed);
            status = ParticipantStatus.DECLINED;
        }
        Instant now = Instant.now();
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, now));

        boolean news = !alreadyDeclined || !trimmed.equals(previousReason == null ? "" : previousReason);
        if (!going && news && !userId.equals(activity.getCreatorId())) {
            eventPublisher.publishEvent(new ActivityInvitationDeclinedEvent(
                    activityId, activity.getCreatorId(), userId, activity.getTitle(),
                    trimmed.isEmpty() ? null : trimmed, now));
        }
        return status;
    }

    /**
     * "Might come": answers an invitation, or a public plan, without taking a place. The host
     * cannot - hosting is going - and 400 says so. Idempotent. Clears an earlier decline's
     * reason, like any other change of answer.
     */
    @Transactional
    public ParticipantStatus maybe(UUID activityId, UUID userId) {
        Activity activity = requireVisible(activityId, userId);
        if (activity.getCreatorId().equals(userId)) {
            throw new IllegalArgumentException("You're hosting this plan, so you're going.");
        }
        ParticipantStatus status = upsert(activityId, userId, ParticipantStatus.MAYBE);
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, Instant.now()));
        return status;
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
        UUID hostId = requireVisible(activityId, viewerId).getCreatorId();

        List<Participant> participants =
                participantRepository.findByIdActivityIdOrderByStatusAsc(activityId);
        Map<UUID, UserSummary> users = userDirectoryService.findByIds(
                participants.stream().map(p -> p.getId().getUserId()).toList());

        return participants.stream()
                .map(participant -> {
                    UUID participantUserId = participant.getId().getUserId();
                    UserSummary summary = users.get(participantUserId);
                    // A reason is said to the host; other guests don't get to read it.
                    boolean mayRead = viewerId.equals(hostId) || viewerId.equals(participantUserId);
                    return new ActivityParticipant(
                            participantUserId,
                            summary == null ? null : summary.username(),
                            participant.getStatus(),
                            mayRead ? participant.getDeclineReason() : null);
                })
                .toList();
    }

    /**
     * Everyone with a stake in the plan: the host and whoever is joined or still invited.
     * Read by the listener that tells them it changed, on its own thread and as the
     * system, so it is not filtered by who is asking.
     */
    @Transactional(readOnly = true)
    public Set<UUID> audienceOf(UUID activityId) {
        Set<UUID> audience = new HashSet<>(participantRepository.findUserIdsByActivityAndStatusIn(
                activityId, ParticipantRepository.IN_THE_PLAN));
        activityRepository.findById(activityId).ifPresent(activity -> audience.add(activity.getCreatorId()));
        return audience;
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
     * Fails with {@link ActivityFullException} when the plan has a cap, it is reached, and the
     * caller isn't one of the people already counted in it.
     * <p>
     * The lock is taken before the count and held to the end of the transaction, so the
     * count-then-insert is one step as far as any other join is concerned. Plans without a
     * cap skip it, and never contend.
     */
    private void requireRoom(Activity activity, UUID userId) {
        Integer max = activity.getMaxParticipants();
        if (max == null) {
            return;
        }
        UUID activityId = activity.getId();
        participantRepository.lockPlan(activityId.toString());
        boolean alreadyIn = participantRepository.findByIdActivityIdAndIdUserId(activityId, userId)
                .map(participant -> participant.getStatus() == ParticipantStatus.JOINED)
                .orElse(false);
        if (alreadyIn) {
            return;
        }
        if (participantRepository.countByIdActivityIdAndStatus(activityId, ParticipantStatus.JOINED) >= max) {
            throw new ActivityFullException();
        }
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
     * through the query repository so there is exactly one definition of "visible" - as
     * an EXISTS, not the full feed projection, which was built here only to be thrown away.
     */
    private Activity requireVisible(UUID activityId, UUID userId) {
        if (!activityQueryRepository.isVisible(activityId, userId)) {
            throw new ActivityNotVisibleException();
        }
        return activityRepository.findById(activityId)
                .orElseThrow(ActivityNotVisibleException::new);
    }
}
