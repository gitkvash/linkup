package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.dto.CancelRequest;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The host's hand on their own plan: start it, end it, cancel it, or say afterwards
 * whether it happened.
 * <p>
 * Starting is the only way a plan goes live - nothing starts by the clock. A one-off its
 * host never starts is put to them {@link ActivityStatusResolver#START_GRACE} after its
 * start time ({@link #checkIn}), and is cancelled by the clock if they don't answer within
 * {@link ActivityStatusResolver#CHECK_IN_WINDOW} (see {@link ActivityStatusResolver}).
 * Ending is for the plan that's over before its window is up; cancelling for the one
 * that won't happen, while keeping it visible to the people who were in it - deleting
 * it removes it for everyone.
 * <p>
 * Only the creator may. A non-creator gets the same {@link ActivityNotVisibleException}
 * (404) that editing someone else's plan gives, rather than a 403 confirming the plan
 * exists.
 * <p>
 * Start, end, cancel and check-in {@code saveAndFlush}: the controller answers each with the
 * read model, read over JDBC in the same transaction, and an unflushed write is invisible to
 * it - the response came back with the status from before the change.
 */
@Service
public class ActivityLifecycleService {

    /** How long after one "Remind everyone" the host must wait for the next. */
    public static final Duration REMIND_COOLDOWN = Duration.ofMinutes(10);

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
        activityRepository.saveAndFlush(activity);

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
        activityRepository.saveAndFlush(activity);
        // Ending notifies nobody, so this is the only way the people in the plan see it end.
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, now.toInstant()));
    }

    /**
     * Marks the plan as not happening, and tells everyone who was joined or invited.
     * Idempotent: cancelling it again changes nothing and tells nobody twice. A plan that
     * already happened can't be cancelled - it is over, not called off.
     * <p>
     * A repeating plan is cancelled as a whole, not one occurrence of it: occurrences
     * aren't rows (see {@code V24}), so there is nothing smaller to cancel.
     * <p>
     * The host may say why ({@code reason}, blank counts as none), which travels in the
     * notification to everyone in the plan. It is not stored on the plan: a cancel that was
     * already done stays as it was, so a repeat with a different reason tells nobody.
     */
    @Transactional
    public void cancel(UUID activityId, UUID actorId, String reason) {
        Activity activity = requireOwn(activityId, actorId);
        if (activity.getCancelledAt() != null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        if (statusOf(activity, now) == ActivityStatus.ENDED) {
            throw new IllegalArgumentException("This plan has already happened, so it can't be cancelled.");
        }
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.length() > CancelRequest.MAX_REASON) {
            throw new IllegalArgumentException(
                    "Keep the reason under " + CancelRequest.MAX_REASON + " characters.");
        }
        activity.setCancelledAt(now);
        activityRepository.saveAndFlush(activity);

        List<UUID> participants = othersInThePlan(activity);
        if (!participants.isEmpty()) {
            eventPublisher.publishEvent(new ActivityCancelledEvent(
                    activityId, actorId, activity.getTitle(), activity.getStartTime(),
                    activity.isHasTime(), participants, trimmed.isEmpty() ? null : trimmed, now.toInstant()));
        }
    }

    /**
     * The host's answer to "did it happen?" for a one-off nobody started.
     * <p>
     * Yes records the run it was planned as - from its start time to its end time or
     * {@link ActivityStatusResolver#DEFAULT_DURATION} later, but never past now - so it
     * reads as ended and counts as a plan done together. No cancels it. Neither pushes
     * anything: the time has already gone by and the people in it were there, or weren't;
     * an {@link ActivityChangedEvent} just brings their screens up to date.
     * <p>
     * Allowed from the moment the question is asked, and still after the window has run
     * out and the clock cancelled it - the host is the authority on whether their own plan
     * happened, as {@link #start} already lets them be. Refused (400) for a repeating
     * plan, which is never asked, and before the question is due. A plan the host already
     * started, ended or cancelled is left as it is: the question was answered.
     */
    @Transactional
    public void checkIn(UUID activityId, UUID actorId, boolean happened) {
        Activity activity = requireOwn(activityId, actorId);
        if (activity.getRepeatFrequency() != null) {
            throw new IllegalArgumentException("A repeating plan doesn't need this.");
        }
        ActivityStatusResolver.Lifecycle lifecycle = ActivityStatusResolver.Lifecycle.of(activity);
        ZonedDateTime now = ZonedDateTime.now();
        if (now.isBefore(ActivityStatusResolver.checkInDueAt(lifecycle))) {
            throw new IllegalArgumentException("This plan isn't over yet. Start it or cancel it instead.");
        }
        if (activity.getStartedAt() != null || activity.getEndedAt() != null || activity.getCancelledAt() != null) {
            return;
        }
        if (happened) {
            ZonedDateTime end = ActivityStatusResolver.scheduledEnd(lifecycle);
            activity.setStartedAt(activity.getStartTime());
            activity.setEndedAt(end.isAfter(now) ? now : end);
        } else {
            activity.setCancelledAt(now);
        }
        activityRepository.saveAndFlush(activity);
        eventPublisher.publishEvent(new ActivityChangedEvent(activityId, now.toInstant()));
    }

    /**
     * The host nudges the people who joined: a push and a notification row for each,
     * naming the plan and when it starts. Refused (400) for a plan that is over or
     * cancelled, when nobody has joined, and within {@link #REMIND_COOLDOWN} of the last
     * one, so the button can't be used to spam. A live plan can still be reminded -
     * "we're at the park" is a real reason - and the message says so.
     *
     * @return how many people were reminded
     */
    @Transactional
    public int remind(UUID activityId, UUID actorId) {
        Activity activity = requireOwn(activityId, actorId);
        ZonedDateTime now = ZonedDateTime.now();
        ActivityStatusResolver.Lifecycle lifecycle = ActivityStatusResolver.Lifecycle.of(activity);
        ActivityStatus status = ActivityStatusResolver.resolve(lifecycle, now);
        if (status.isOver() || ActivityStatusResolver.needsCheckIn(lifecycle, now)) {
            throw new IllegalArgumentException("This plan is over, so there is nothing to remind anyone of.");
        }
        ZonedDateTime last = activity.getRemindedAt();
        if (last != null && last.plus(REMIND_COOLDOWN).isAfter(now)) {
            long minutes = Math.max(1, Duration.between(now, last.plus(REMIND_COOLDOWN)).toMinutes() + 1);
            throw new IllegalArgumentException(
                    "You reminded everyone a moment ago. You can do it again in " + minutes + " min.");
        }
        List<UUID> going = participantRepository
                .findUserIdsByActivityAndStatusIn(activityId, Set.of(ParticipantStatus.JOINED))
                .stream()
                .filter(userId -> !userId.equals(activity.getCreatorId()))
                .toList();
        if (going.isEmpty()) {
            throw new IllegalArgumentException("Nobody has joined this plan yet.");
        }
        boolean live = status == ActivityStatus.LIVE;
        ZonedDateTime start = live ? null : ActivityStatusResolver.nextStartAfter(lifecycle, now);
        activity.setRemindedAt(now);
        activityRepository.save(activity);
        eventPublisher.publishEvent(new ActivityRemindedEvent(
                activityId, actorId, activity.getTitle(), start == null ? activity.getStartTime() : start,
                activity.isHasTime(), live, going, now.toInstant()));
        return going.size();
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
