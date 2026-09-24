package ge.kcamp.linkup.identity.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private RateLimiter limiter(int capacity, Duration period, int maxKeys) {
        return new RateLimiter("test", capacity, period, maxKeys, now::get);
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    @Test
    void allowsCapacityThenRefusesWithRetryAfter() {
        RateLimiter limiter = limiter(3, Duration.ofMinutes(1), 100);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        }
        RateLimiter.Decision refused = limiter.tryAcquire("a");

        assertThat(refused.allowed()).isFalse();
        // One token comes back every 20 s.
        assertThat(refused.retryAfterSeconds()).isEqualTo(20);
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1), 100);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void refillsOverThePeriod() {
        RateLimiter limiter = limiter(2, Duration.ofMinutes(1), 100);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        advance(Duration.ofSeconds(30));

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        RateLimiter limiter = limiter(1000, Duration.ofSeconds(1), 100);
        for (int i = 0; i < 1000; i++) {
            limiter.tryAcquire("a");
        }

        RateLimiter.Decision refused = limiter.tryAcquire("a");

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(1);
    }

    /** Memory can't grow with every key ever seen: idle keys are dropped by the sweep. */
    @Test
    void idleKeysAreEvicted() {
        RateLimiter limiter = limiter(5, Duration.ofMinutes(1), 1000);
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(100);

        advance(Duration.ofMinutes(1).plusSeconds(1));
        limiter.tryAcquire("fresh");

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    /** A key still being limited must survive a sweep, or the sweep would reset it. */
    @Test
    void sweepKeepsKeysThatAreStillLimited() {
        RateLimiter limiter = limiter(60, Duration.ofMinutes(1), 1000);
        for (int i = 0; i < 60; i++) {
            limiter.tryAcquire("busy");
        }
        advance(Duration.ofSeconds(30));
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire("busy");
        }

        advance(Duration.ofSeconds(31));
        limiter.tryAcquire("other");

        assertThat(limiter.trackedKeys()).isEqualTo(2);
    }

    @Test
    void atTheKeyCapNewKeysPassUntrackedAndKnownKeysStayLimited() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1), 2);
        limiter.tryAcquire("a");
        limiter.tryAcquire("b");

        assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        assertThat(limiter.trackedKeys()).isEqualTo(2);
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void concurrentCallersNeverOverspend() throws Exception {
        RateLimiter limiter = new RateLimiter("test", 50, Duration.ofHours(1), 100);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 400; i++) {
                pool.submit(() -> {
                    start.await();
                    if (limiter.tryAcquire("shared").allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed.get()).isEqualTo(50);
    }
}
