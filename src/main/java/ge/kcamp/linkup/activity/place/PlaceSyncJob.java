package ge.kcamp.linkup.activity.place;

import ge.kcamp.linkup.activity.place.PlaceSyncRepository.CuratedPlace;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps {@code places} in step with OpenStreetMap: fetches the candidates in each
 * {@code linkup.places.sync.areas} rectangle from Overpass, keeps the well-known ones
 * ({@link OsmPlaceClassifier}), and writes them as {@code source = 'OSM'} rows (V32).
 * <p>
 * Runs when the last successful sync is older than {@code linkup.places.sync.interval}
 * (a week). This is checked shortly after startup and then every
 * {@code linkup.places.sync.check-every}. So a fresh database gets its places a minute
 * after the first boot, a restart mid-week does nothing, and a failed sync is retried an
 * hour later rather than a week later. The last success is stored in {@code place_sync},
 * not in memory, which is what lets a restart know.
 * <p>
 * One sync, in order:
 * <ol>
 *   <li>Fetch, outside any transaction. Overpass can take a minute, and a pooled
 *       connection shouldn't wait on it.</li>
 *   <li>In one transaction: lock {@code place_sync} and check again that a sync is still
 *       due, since another instance may just have finished one. Then upsert, delete the OSM
 *       rows this sync didn't see, relink every plan's place, and record the success.</li>
 * </ol>
 * A curated (V31) place wins over its OSM twin, matched by {@code osm_ref} or by kind and
 * distance. An answer much smaller than what is stored is written but doesn't delete
 * anything: Overpass has answered partially before, and one bad week shouldn't strip the
 * map.
 * <p>
 * Everything runs on {@code applicationTaskExecutor}, so it is stamped {@code SYSTEM}
 * like other background work. It has its own ticker thread rather than
 * {@code @EnableScheduling}, for the reason {@code EventPublicationResubmitter} gives.
 */
@Component
class PlaceSyncJob {

    private static final Logger log = LoggerFactory.getLogger(PlaceSyncJob.class);

    /**
     * Below this fraction of the stored OSM places, an answer is presumed partial and
     * deletes nothing.
     */
    static final double MIN_ANSWER_FRACTION_TO_PRUNE = 0.5;

    private final OverpassClient overpass;
    private final PlaceSyncRepository repository;
    private final TransactionTemplate transaction;
    private final TaskExecutor executor;
    private final boolean enabled;
    private final List<SyncArea> areas;
    private final Duration interval;
    private final Duration checkEvery;
    private final Duration initialDelay;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService ticker;

    @Autowired
    PlaceSyncJob(
            OverpassClient overpass,
            PlaceSyncRepository repository,
            PlatformTransactionManager transactionManager,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            @Value("${linkup.places.sync.enabled:true}") boolean enabled,
            @Value("${linkup.places.sync.areas:}") String areas,
            @Value("${linkup.places.sync.interval:P7D}") Duration interval,
            @Value("${linkup.places.sync.check-every:PT1H}") Duration checkEvery,
            @Value("${linkup.places.sync.initial-delay:PT1M}") Duration initialDelay) {
        this(overpass, repository, new TransactionTemplate(transactionManager), executor, enabled,
                SyncArea.parseAll(areas), interval, checkEvery, initialDelay, Clock.systemUTC());
    }

    PlaceSyncJob(
            OverpassClient overpass,
            PlaceSyncRepository repository,
            TransactionTemplate transaction,
            TaskExecutor executor,
            boolean enabled,
            List<SyncArea> areas,
            Duration interval,
            Duration checkEvery,
            Duration initialDelay,
            Clock clock) {
        this.overpass = overpass;
        this.repository = repository;
        this.transaction = transaction;
        this.executor = executor;
        this.enabled = enabled;
        this.areas = areas;
        this.interval = interval;
        this.checkEvery = checkEvery;
        this.initialDelay = initialDelay;
        this.clock = clock;
    }

    /** Off with {@code linkup.places.sync.enabled=false}, or with no areas to sync. */
    @PostConstruct
    void start() {
        if (!enabled || areas.isEmpty() || checkEvery.isZero() || checkEvery.isNegative()) {
            log.info("OSM place sync is off (enabled={}, {} areas)", enabled, areas.size());
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "place-sync");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(this::tick,
                initialDelay.toMillis(), checkEvery.toMillis(), TimeUnit.MILLISECONDS);
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
            executor.execute(this::syncIfDue);
        } catch (TaskRejectedException e) {
            running.set(false);
            log.debug("Skipping place sync: {}", e.getMessage());
        }
    }

    void syncIfDue() {
        try {
            if (!isDue(repository.lastSuccess(), now(), interval)) {
                return;
            }
            List<OsmPlace> candidates = OsmPlaceClassifier.dedupe(overpass.fetch(areas).stream()
                    .map(OsmPlaceClassifier::classify)
                    .flatMap(Optional::stream)
                    .toList());
            transaction.executeWithoutResult(status -> apply(candidates));
        } catch (RuntimeException e) {
            // Must not kill the schedule; the next check retries.
            log.warn("OSM place sync failed, retrying in {}: {}", checkEvery, e.toString());
        } finally {
            running.set(false);
        }
    }

    private void apply(List<OsmPlace> candidates) {
        if (!isDue(repository.lockAndReadLastSuccess(), now(), interval)) {
            log.debug("Another instance just synced places; skipping");
            return;
        }

        List<OsmPlace> places = withoutCurated(candidates, repository.curatedPlaces());
        int stored = repository.countSyncedPlaces();
        if (places.isEmpty() && stored > 0) {
            // A working Overpass has never answered "nothing" for a city. Don't record a
            // success, so the next check tries again.
            log.warn("OSM place sync got no places back ({} stored); keeping them and retrying", stored);
            return;
        }

        repository.upsert(places);

        int deleted = 0;
        if (places.size() >= stored * MIN_ANSWER_FRACTION_TO_PRUNE) {
            deleted = repository.deleteNotSeenThisSync();
        } else {
            log.warn("OSM place sync got {} places where {} are stored; not deleting any, in case the "
                    + "answer was partial. If OSM really lost them, delete the rows by hand.",
                    places.size(), stored);
        }
        int relinked = repository.relinkLocations();
        repository.markSuccess();

        log.info("OSM place sync: {} places ({} candidates, {} left to curated rows), {} removed, "
                        + "{} plans relinked",
                places.size(), candidates.size(), candidates.size() - places.size(), deleted, relinked);
    }

    static boolean isDue(Optional<OffsetDateTime> lastSuccess, OffsetDateTime now, Duration interval) {
        return lastSuccess.map(last -> !last.plus(interval).isAfter(now)).orElse(true);
    }

    /**
     * Drops a candidate that is a curated place already: the same OSM feature, or one of
     * the same kind inside a curated place's radius. The second catches the same place
     * mapped twice in OSM, or re-mapped under a new id since V31 was written. A different
     * kind can overlap. A stadium inside a park is still its own place.
     */
    static List<OsmPlace> withoutCurated(List<OsmPlace> candidates, List<CuratedPlace> curated) {
        return candidates.stream()
                .filter(candidate -> curated.stream().noneMatch(place ->
                        candidate.osmRef().equals(place.osmRef())
                                || (place.kind() == candidate.kind()
                                && OsmPlaceClassifier.distanceM(place.lat(), place.lng(),
                                candidate.lat(), candidate.lng()) <= place.radiusM())))
                .toList();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
