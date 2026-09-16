package ge.kcamp.linkup.nlp.internal;

import com.zoho.hawking.HawkingTimeParser;
import ge.kcamp.linkup.nlp.NlpUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Hands out {@link HawkingTimeParser} instances one caller at a time.
 * <p>
 * Hawking wraps a Stanford CoreNLP pipeline, which is <em>not</em> thread-safe, yet a
 * single instance was shared by every request to {@code POST /activities/from-text}.
 * Two concurrent creates were corrupting each other's parse state.
 * <p>
 * Instances are created lazily and capped, because each one loads its own copy of the
 * models (visible in the logs as "Loading POS tagger ... done"). Beyond the cap, callers
 * queue rather than allocate: bounded latency is preferable to unbounded memory.
 */
class HawkingParserPool {

    private static final Logger log = LoggerFactory.getLogger(HawkingParserPool.class);

    private final BlockingQueue<HawkingTimeParser> idle;
    private final AtomicInteger created = new AtomicInteger();
    private final int maxSize;
    private final long borrowTimeoutMillis;

    HawkingParserPool(int maxSize, long borrowTimeoutMillis) {
        this.maxSize = Math.max(1, maxSize);
        this.borrowTimeoutMillis = borrowTimeoutMillis;
        this.idle = new ArrayBlockingQueue<>(this.maxSize);
    }

    <T> T withParser(Function<HawkingTimeParser, T> work) {
        HawkingTimeParser parser = borrow();
        try {
            return work.apply(parser);
        } finally {
            // offer, not put: the queue is sized to maxSize so this cannot block, and
            // dropping a parser is preferable to hanging a request thread.
            idle.offer(parser);
        }
    }

    private HawkingTimeParser borrow() {
        HawkingTimeParser parser = idle.poll();
        if (parser != null) {
            return parser;
        }
        if (tryReserveNewInstance()) {
            log.info("Creating Hawking parser instance {} of at most {}", created.get(), maxSize);
            return new HawkingTimeParser();
        }
        try {
            parser = idle.poll(borrowTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NlpUnavailableException();
        }
        if (parser == null) {
            throw new NlpUnavailableException();
        }
        return parser;
    }

    /** CAS loop rather than incrementAndGet, so the cap can't be overshot under load. */
    private boolean tryReserveNewInstance() {
        while (true) {
            int current = created.get();
            if (current >= maxSize) {
                return false;
            }
            if (created.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }
}
