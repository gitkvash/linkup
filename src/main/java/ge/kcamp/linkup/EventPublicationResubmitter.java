package ge.kcamp.linkup;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retries event publications whose listener failed, while the application is running.
 * <p>
 * {@code republish-outstanding-events-on-restart} only retries at startup. Between
 * restarts a failed listener - a notification row that couldn't be written, a feed
 * fan-out that lost Redis, a task the saturated executor refused - left its publication
 * incomplete in {@code event_publication} until the next deploy, which on a quiet week
 * could be days. This resubmits anything incomplete and older than
 * {@code linkup.events.resubmit-older-than}, every {@code linkup.events.resubmit-interval}.
 * <p>
 * The age is what keeps it from racing live deliveries: a publication younger than that
 * may simply still be queued or running. One that is resubmitted while still in flight
 * (a listener stuck for longer than the age) gets delivered twice, which the listeners
 * tolerate - notification rows dedupe on {@code dedupe_key}, and timeline writes are
 * idempotent. The same is true when several instances run this at once.
 * <p>
 * The resubmission itself runs on {@code applicationTaskExecutor}, not on the ticker
 * thread, so it is stamped {@code SYSTEM} by that executor's decorator like every other
 * piece of background work; nothing here calls {@link DatabaseRole#runAsSystem}. Its own
 * thread rather than {@code @EnableScheduling}, for the same reason
 * {@code SseEmitterRegistry} has one: nothing else needs a scheduler, and enabling one
 * app-wide would put an unstamped thread pool behind any future {@code @Scheduled}.
 */
@Component
class EventPublicationResubmitter {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationResubmitter.class);

    private final IncompleteEventPublications incompletePublications;
    private final TaskExecutor executor;
    private final Duration minAge;
    private final Duration interval;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService ticker;

    EventPublicationResubmitter(
            IncompleteEventPublications incompletePublications,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            @Value("${linkup.events.resubmit-older-than:PT5M}") Duration minAge,
            @Value("${linkup.events.resubmit-interval:PT2M}") Duration interval) {
        this.incompletePublications = incompletePublications;
        this.executor = executor;
        this.minAge = minAge;
        this.interval = interval;
    }

    /** Disabled by setting {@code linkup.events.resubmit-interval} to zero. */
    @PostConstruct
    void start() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "event-resubmitter");
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

    /** Hands the work to the executor, at most one run at a time. */
    void tick() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::resubmit);
        } catch (TaskRejectedException e) {
            // Saturated or shutting down - the very backlog this would add to. Next tick.
            running.set(false);
            log.debug("Skipping event resubmission: {}", e.getMessage());
        }
    }

    void resubmit() {
        try {
            incompletePublications.resubmitIncompletePublicationsOlderThan(minAge);
        } catch (RuntimeException e) {
            // Must not kill the schedule; the next tick tries again.
            log.warn("Resubmitting incomplete event publications failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
