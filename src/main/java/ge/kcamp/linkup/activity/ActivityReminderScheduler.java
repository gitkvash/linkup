package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.entity.Activity;
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
 * Publishes {@link ActivityStartingSoonEvent} for every occurrence that starts within
 * {@code linkup.notification.reminder-lead} (30 minutes), for the people who joined it.
 * <p>
 * Each scan covers the slice of time the previous one didn't: {@code (scannedUpTo,
 * now + lead]}. So a reminder goes out once, about {@code lead} ahead, rather than on
 * every scan for half an hour - and a plan created with less than {@code lead} to go gets
 * none, having just been made (its invitees were told when). The first scan after a start
 * covers everything from now on, which catches up whatever was due while the instance was
 * down or asleep. A reminder that gets picked up twice - on a restart, or by two instances
 * during a deploy - dedupes on its notification key, which is built from the occurrence's
 * start time (see the event).
 * <p>
 * The scan runs on {@code applicationTaskExecutor}, so it is stamped {@code SYSTEM} like
 * every other piece of background work: it reads everyone's plans, with no user acting.
 * Its own ticker thread rather than {@code @EnableScheduling}, for the reason
 * {@code EventPublicationResubmitter} gives.
 * <p>
 * The window lives in memory, so this reminds only while an instance is running. A host
 * that sleeps when idle (Render's free tier does) sends a late reminder or none.
 */
@Component
class ActivityReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActivityReminderScheduler.class);

    private static final Set<ParticipantStatus> GOING = Set.of(ParticipantStatus.JOINED);

    private final ActivityRepository activityRepository;
    private final ParticipantRepository participantRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final TaskExecutor executor;
    private final Duration lead;
    private final Duration interval;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    /** Where the last scan's window ended. Written only by the one scan allowed to run. */
    private volatile ZonedDateTime scannedUpTo;
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
        this.lead = lead;
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
            // Never reaching back before now: an occurrence that has already started is
            // past reminding, however long ago the last scan was.
            ZonedDateTime from = scannedUpTo == null || scannedUpTo.isBefore(now) ? now : scannedUpTo;
            ZonedDateTime to = now.plus(lead);
            if (!to.isAfter(from)) {
                return;
            }
            transaction.executeWithoutResult(status -> remind(from, to));
            scannedUpTo = to;
        } catch (RuntimeException e) {
            // Must not kill the schedule. The window stays put, so the next scan retries it.
            log.warn("Activity reminder scan failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /** In a transaction, so the publications are recorded with it (Modulith's registry). */
    private void remind(ZonedDateTime from, ZonedDateTime to) {
        for (Activity activity : activityRepository.findReminderCandidates(from, to)) {
            ZonedDateTime start = ActivityStatusResolver.nextStartAfter(lifecycleOf(activity), from);
            if (start == null || start.isAfter(to)) {
                continue;
            }
            List<UUID> going = participantRepository.findUserIdsByActivityAndStatusIn(activity.getId(), GOING);
            if (going.isEmpty()) {
                continue;
            }
            eventPublisher.publishEvent(new ActivityStartingSoonEvent(
                    activity.getId(), activity.getTitle(), start, going, start.toInstant()));
        }
    }

    private static ActivityStatusResolver.Lifecycle lifecycleOf(Activity activity) {
        return new ActivityStatusResolver.Lifecycle(
                activity.getStartTime(), activity.getEndTime(), activity.isHasTime(),
                activity.getRepeatFrequency(), activity.getRepeatInterval(), activity.getRepeatUntil(),
                activity.getStartedAt(), activity.getEndedAt());
    }
}
