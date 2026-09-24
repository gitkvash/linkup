package ge.kcamp.linkup.identity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A keyed token bucket: each key may spend {@code capacity} requests at once and gets them
 * back evenly over {@code period}. In memory and dependency-free on purpose.
 * <p>
 * <strong>The state is per instance.</strong> There is one Render instance today, so a
 * limit here is the limit. Scaling out multiplies every limit by the instance count; at
 * that point this belongs in Redis, which the feed already uses.
 * <p>
 * Memory is bounded two ways, because the keys (client IPs, typed usernames) are chosen by
 * whoever is sending the requests:
 * <ul>
 *   <li>A bucket that has refilled completely is indistinguishable from no bucket, so a
 *       sweep - at most once per {@code period}, piggybacked on a request - drops those.
 *       Any key idle for a whole period is gone after the next sweep.</li>
 *   <li>At {@code maxKeys}, a key with no bucket is let through untracked rather than
 *       tracked or refused. Refusing would let a flood of new keys lock everyone out;
 *       sweeping on demand would make every request in that flood pay for an O(n) scan.
 *       Reaching the cap means the sender already controls that many keys, which a
 *       per-key limit can't stop anyway - the other limits in front of it still apply.</li>
 * </ul>
 * Every read-modify-write of a bucket happens inside {@link ConcurrentHashMap#compute},
 * so a sweep can never remove a bucket between another thread reading and spending it.
 */
public final class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Outcome of one attempt. {@code retryAfterSeconds} is 0 when allowed, else at least 1. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final String name;
    private final int capacity;
    private final long periodNanos;
    private final int maxKeys;
    private final LongSupplier nanoClock;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepAt;
    private final AtomicLong nextFullWarningAt;

    public RateLimiter(String name, int capacity, Duration period, int maxKeys) {
        this(name, capacity, period, maxKeys, System::nanoTime);
    }

    RateLimiter(String name, int capacity, Duration period, int maxKeys, LongSupplier nanoClock) {
        if (capacity < 1 || period.isNegative() || period.isZero() || maxKeys < 1) {
            throw new IllegalStateException("Rate limit '" + name + "' needs a positive capacity, period and key cap");
        }
        this.name = name;
        this.capacity = capacity;
        this.periodNanos = period.toNanos();
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
        long now = nanoClock.getAsLong();
        this.nextSweepAt = new AtomicLong(now + periodNanos);
        this.nextFullWarningAt = new AtomicLong(now);
    }

    /** Spends one request for {@code key}, if it has one left. */
    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        sweepIfDue(now);

        if (buckets.size() >= maxKeys && !buckets.containsKey(key)) {
            warnFull(now);
            return Decision.ALLOWED;
        }

        Decision[] decision = new Decision[1];
        buckets.compute(key, (k, bucket) -> {
            Bucket current = bucket == null ? new Bucket(capacity, now) : bucket;
            decision[0] = current.take(now);
            return current;
        });
        return decision[0];
    }

    /** Buckets currently held. For tests and diagnostics. */
    int trackedKeys() {
        return buckets.size();
    }

    private void sweepIfDue(long now) {
        long due = nextSweepAt.get();
        // Only the thread that wins the CAS sweeps; everyone else carries on.
        if (now - due < 0 || !nextSweepAt.compareAndSet(due, now + periodNanos)) {
            return;
        }
        for (String key : buckets.keySet()) {
            buckets.computeIfPresent(key, (k, bucket) -> bucket.isFull(now) ? null : bucket);
        }
    }

    private void warnFull(long now) {
        long due = nextFullWarningAt.get();
        if (now - due >= 0 && nextFullWarningAt.compareAndSet(due, now + periodNanos)) {
            log.warn("Rate limit '{}' is tracking its maximum of {} keys; new keys pass unlimited until the next sweep",
                    name, maxKeys);
        }
    }

    /** Only ever touched inside a {@code compute} on its key, so it needs no lock of its own. */
    private final class Bucket {
        private double tokens;
        private long updatedAt;

        Bucket(int tokens, long now) {
            this.tokens = tokens;
            this.updatedAt = now;
        }

        Decision take(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return Decision.ALLOWED;
            }
            // Until one whole token is back, rounded up: a client that waits exactly this
            // long gets in.
            double waitNanos = (1 - tokens) * periodNanos / capacity;
            long waitSeconds = (long) Math.ceil(waitNanos / TimeUnit.SECONDS.toNanos(1));
            return new Decision(false, Math.max(1, waitSeconds));
        }

        boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            long elapsed = now - updatedAt;
            if (elapsed > 0) {
                // Multiplied before dividing: a precomputed per-nanosecond rate is inexact
                // in binary, and would leave a bucket at 0.9999... tokens after exactly
                // the time it takes to earn one.
                tokens = Math.min(capacity, tokens + (double) elapsed * capacity / periodNanos);
                updatedAt = now;
            }
        }
    }
}
