package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The host's hand on their own plan: start it, end it.
 * <p>
 * Both are the exception rather than the rule - a plan nobody touches still starts and
 * ends on its own (see {@link ActivityStatusResolver}). These exist for the two cases the
 * clock gets wrong: it began early, or it's over well before its window was up.
 * <p>
 * Only the creator may. A non-creator gets the same {@link ActivityNotVisibleException}
 * (404) that editing someone else's plan gives, rather than a 403 confirming the plan
 * exists.
 */
@Service
public class ActivityLifecycleService {

    private final ActivityRepository activityRepository;
    private final ParticipantRepository participantRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ActivityLifecycleService(
            ActivityRepository activityRepository,
            ParticipantRepository participantRepository,
            ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.participantRepository = participantRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Idempotent: starting something already started keeps the first timestamp, because
     * the window runs from it and a double tap must not extend the plan by an hour.
     * Starting something already ended reopens it - the host is the authority on whether
     * their own plan is over.
     * <p>
     * Only the first start publishes {@link ActivityStartedEvent}: a repeat keeps the
     * timestamp, and a reopened plan already told everyone once.
     */
    @Transactional
    public void start(UUID activityId, UUID actorId) {
        Activity activity = requireOwn(activityId, actorId);
        activity.setEndedAt(null);
        boolean firstStart = activity.getStartedAt() == null;
        if (firstStart) {
            activity.setStartedAt(ZonedDateTime.now());
        }
        activityRepository.save(activity);

        if (firstStart) {
            List<UUID> participants = participantRepository
                    .findUserIdsByActivityAndStatusIn(activityId, ParticipantRepository.IN_THE_PLAN)
                    .stream()
                    .filter(userId -> !userId.equals(actorId))
                    .toList();
            if (!participants.isEmpty()) {
                eventPublisher.publishEvent(new ActivityStartedEvent(
                        activityId, actorId, activity.getTitle(), participants,
                        activity.getStartedAt().toInstant()));
            }
        }
    }

    /**
     * Ending something that never formally started is allowed and sets both: a plan that
     * ran its two hours and is now over was live without anyone saying so, and recording
     * only the end would leave a row claiming it never happened.
     */
    @Transactional
    public void end(UUID activityId, UUID actorId) {
        Activity activity = requireOwn(activityId, actorId);
        ZonedDateTime now = ZonedDateTime.now();
        if (activity.getStartedAt() == null) {
            activity.setStartedAt(activity.getStartTime().isBefore(now) ? activity.getStartTime() : now);
        }
        activity.setEndedAt(now);
        activityRepository.save(activity);
    }

    private Activity requireOwn(UUID activityId, UUID actorId) {
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(ActivityNotVisibleException::new);
        if (!activity.getCreatorId().equals(actorId)) {
            throw new ActivityNotVisibleException();
        }
        return activity;
    }
}
