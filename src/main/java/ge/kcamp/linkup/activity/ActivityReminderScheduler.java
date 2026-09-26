package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes two notices for every occurrence with a clock time, for the people who joined
 * it: {@link ActivityStartingSoonEvent} {@code linkup.notification.reminder-lead} (30
 * minutes) ahead, and {@link ActivityStartingNowEvent} when it reaches its start time.
 * Only for a plan that is still upcoming - one the host already started, ended or
 * cancelled gets neither. The second matters more than it did: a plan no longer starts
 * by itself, so it is also the host's cue to start it.
 * <p>
 * Each notice has its own window, and each scan covers the slice of it the previous scan
 * didn't: {@code (scannedUpTo, now + lead]}. So a notice goes out once rather than on
 * every scan - and a plan created with less than the lead to go gets no 30-minute one,
 * having just been made (its invitees were told when). The first scan after a start
 * catches up whatever was due while the instance was down or asleep, as far back as the
 * window's {@code catchUp}: never before now for "starts in 30 minutes", a few minutes
 * for "starting now". A notice that gets picked up twice - on a restart, or by two
 * instances during a deploy - dedupes on its notification key, which is built from the
 * occurrence's start time (see the events).
 * <p>
 * The scan runs on {@code applicationTaskExecutor}, so it is stamped {@code SYSTEM} like
 * every other piece of background work: it reads everyone's plans, with no user acting.
 * Its own ticker thread rather than {@code @EnableScheduling}, for the reason
 * {@code EventPublicationResubmitter} gives.
 * <p>
 * The windows live in memory, so this notifies only while an instance is running. A host
 * that sleeps when idle sends a late notice or none.
 */
@Component
class ActivityReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActivityReminderScheduler.class);

    private static final Set<ParticipantStatus> GOING = Set.of(ParticipantStatus.JOINED);

    /**
     * How late a "starting now" may still go out. Enough to cover a scan interval and a
     * short restart; past it the notice is stale, and the plan may well be under way.
     */
    static final Duration STARTING_NOW_CATCH_UP = Duration.ofMinutes(5);

    private final ActivityRepository activityRepository;
    private final ParticipantRepository participantRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final TaskExecutor executor;
    private final Window soon;
    private final Window startingNow;
    private final Duration interval;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    private ScheduledExecutorService ticker;

    @Autowired
    ActivityReminderScheduler(
            ActivityRepository activityRepository,
            ParticipantRepository participantRepository,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            @Value("${linkup.notification.reminder-lead:PT30M}") Duration lead,
            @Value("${linkup.notification.reminder-interval:PT1M}") Duration interval) {
        this(activityRepository, participantRepository, eventPublisher,
                new TransactionTemplate(transactionManager), executor, lead, interval, Clock.systemUTC());
    }

    ActivityReminderScheduler(
            ActivityRepository activityRepository,
            ParticipantRepository participantRepository,
            ApplicationEventPublisher eventPublisher,
            TransactionTemplate transaction,
            TaskExecutor executor,
            Duration lead,
            Duration interval,
            Clock clock) {
        this.activityRepository = activityRepository;
        this.participantRepository = participantRepository;
        this.eventPublisher = eventPublisher;
        this.transaction = transaction;
        this.executor = executor;
        this.soon = new Window(lead, lead);
        this.startingNow = new Window(Duration.ZERO, STARTING_NOW_CATCH_UP);
        this.interval = interval;
        this.clock = clock;
    }

    /** Disabled by setting {@code linkup.notification.reminder-interval} to zero. */
    @PostConstruct
    void start() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "activity-reminders");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        ticker.scheduleWithFixedDelay(this::tick, millis, millis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
    }

    /** Hands the scan to the executor, at most one at a time. */
    void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::scan);
        } catch (TaskRejectedException e) {
            // The window isn't advanced, so the next tick covers this one's slice too.
            running.set(false);
            log.debug("Skipping reminder scan: {}", e.getMessage());
        }
    }

    void scan() {
        try {
            ZonedDateTime now = ZonedDateTime.now(clock);
            ZonedDateTime soonFrom = soon.from(now);
            ZonedDateTime soonTo = now.plus(soon.lead);
            ZonedDateTime nowFrom = startingNow.from(now);
            ZonedDateTime nowTo = now.plus(startingNow.lead);
            transaction.executeWithoutResult(status -> {
                if (soonTo.isAfter(soonFrom)) {
                    notify(soonFrom, soonTo, now, (activity, start, going) -> new ActivityStartingSoonEvent(
                            activity.getId(), activity.getTitle(), start, going, start.toInstant()));
                }
                if (nowTo.isAfter(nowFrom)) {
                    notify(nowFrom, nowTo, now, (activity, start, going) -> new ActivityStartingNowEvent(
                            activity.getId(), activity.getCreatorId(), activity.getTitle(), start, going,
                            start.toInstant()));
                }
            });
            soon.scannedUpTo = soonTo;
            startingNow.scannedUpTo = nowTo;
        } catch (RuntimeException e) {
            // Must not kill the schedule. The windows stay put, so the next scan retries them.
            log.warn("Activity reminder scan failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * In a transaction, so the publications are recorded with it (Modulith's registry).
     * Skips anything not upcoming right now: a plan already live needs no "starts soon",
     * and one that is over or cancelled needs nothing at all.
     */
    private void notify(ZonedDateTime from, ZonedDateTime to, ZonedDateTime now, Notice notice) {
        for (Activity activity : activityRepository.findReminderCandidates(from, to)) {
            ActivityStatusResolver.Lifecycle lifecycle = ActivityStatusResolver.Lifecycle.of(activity);
            ZonedDateTime start = ActivityStatusResolver.nextStartAfter(lifecycle, from);
            if (start == null || start.isAfter(to)) {
                continue;
            }
            if (ActivityStatusResolver.resolve(lifecycle, now) != ActivityStatus.UPCOMING) {
                continue;
            }
            List<UUID> going = participantRepository.findUserIdsByActivityAndStatusIn(activity.getId(), GOING);
            if (going.isEmpty()) {
                continue;
            }
            eventPublisher.publishEvent(notice.of(activity, start, going));
        }
    }

    @FunctionalInterface
    private interface Notice {
        Object of(Activity activity, ZonedDateTime start, List<UUID> going);
    }

    /**
     * One notice's slice of the timeline: occurrences starting in {@code (from, now + lead]}.
     * {@code catchUp} is how far behind {@code now + lead} a scan may reach - so how late
     * the notice may be, after a gap between scans.
     */
    private static final class Window {
        private final Duration lead;
        private final Duration catchUp;

        /** Where the last scan ended. Written only by the one scan allowed to run. */
        private volatile ZonedDateTime scannedUpTo;

        Window(Duration lead, Duration catchUp) {
            this.lead = lead;
            this.catchUp = catchUp;
        }

        ZonedDateTime from(ZonedDateTime now) {
            ZonedDateTime floor = now.plus(lead).minus(catchUp);
            return scannedUpTo == null || scannedUpTo.isBefore(floor) ? floor : scannedUpTo;
        }
    }
}
