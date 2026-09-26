package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
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
 * The host's hand on their own plan: start it, end it, cancel it.
 * <p>
 * Starting is the only way a plan goes live - nothing starts by the clock. A plan its
 * host never starts is cancelled by the clock instead, {@link ActivityStatusResolver#START_GRACE}
 * after its start time (see {@link ActivityStatusResolver}). Ending is for the plan that's
 * over before its window is up; cancelling for the one that won't happen, while keeping
 * it visible to the people who were in it - deleting it removes it for everyone.
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
     * Idempotent while it is live: starting something already running keeps the first
     * timestamp, because the window runs from it and a double tap must not extend the
     * plan by an hour. Anything else starts a fresh run - an upcoming plan, one the clock
     * cancelled because its host was late, a repeating plan's next occurrence, or one
     * the host ended or cancelled and has changed their mind about. The host is the
     * authority on whether their own plan is happening.
     * <p>
     * Every fresh run publishes {@link ActivityStartedEvent}; a double tap doesn't.
     */
    @Transactional
    public void start(UUID activityId, UUID actorId) {
        Activity activity = requireOwn(activityId, actorId);
        ZonedDateTime now = ZonedDateTime.now();
        if (statusOf(activity, now) == ActivityStatus.LIVE) {
            return;
        }
        activity.setStartedAt(now);
        activity.setEndedAt(null);
        activity.setCancelledAt(null);
        activityRepository.save(activity);

        List<UUID> participants = othersInThePlan(activity);
        if (!participants.isEmpty()) {
            eventPublisher.publishEvent(new ActivityStartedEvent(
                    activityId, actorId, activity.getTitle(), participants, now.toInstant()));
        }
    }

    /**
     * Ending something that isn't live sets both timestamps: the host is saying it
     * happened and is over, and recording only the end would leave a row claiming it
     * never began. Its run is then zero-length, which is the truth as far as anyone told
     * the app.
     */
    @Transactional
    public void end(UUID activityId, UUID actorId) {
        Activity activity = requireOwn(activityId, actorId);
        ZonedDateTime now = ZonedDateTime.now();
        if (statusOf(activity, now) != ActivityStatus.LIVE) {
            activity.setStartedAt(now);
            activity.setCancelledAt(null);
        }
        activity.setEndedAt(now);
        activityRepository.save(activity);
    }

    /**
     * Marks the plan as not happening, and tells everyone who was joined or invited.
     * Idempotent: cancelling it again changes nothing and tells nobody twice. A plan that
     * already happened can't be cancelled - it is over, not called off.
     * <p>
     * A repeating plan is cancelled as a whole, not one occurrence of it: occurrences
     * aren't rows (see {@code V24}), so there is nothing smaller to cancel.
     */
    @Transactional
    public void cancel(UUID activityId, UUID actorId) {
        Activity activity = requireOwn(activityId, actorId);
        if (activity.getCancelledAt() != null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        if (statusOf(activity, now) == ActivityStatus.ENDED) {
            throw new IllegalArgumentException("This plan has already happened, so it can't be cancelled.");
        }
        activity.setCancelledAt(now);
        activityRepository.save(activity);

        List<UUID> participants = othersInThePlan(activity);
        if (!participants.isEmpty()) {
            eventPublisher.publishEvent(new ActivityCancelledEvent(
                    activityId, actorId, activity.getTitle(), activity.getStartTime(),
                    activity.isHasTime(), participants, now.toInstant()));
        }
    }

    private static ActivityStatus statusOf(Activity activity, ZonedDateTime now) {
        return ActivityStatusResolver.resolve(ActivityStatusResolver.Lifecycle.of(activity), now);
    }

    /** Everyone joined or still invited, the host excluded. */
    private List<UUID> othersInThePlan(Activity activity) {
        return participantRepository
                .findUserIdsByActivityAndStatusIn(activity.getId(), ParticipantRepository.IN_THE_PLAN)
                .stream()
                .filter(userId -> !userId.equals(activity.getCreatorId()))
                .toList();
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
