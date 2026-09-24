package ge.kcamp.linkup.notification.sse;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-user list of open SSE connections (a few allowed, one per device/tab). The
 * registry cleans up on completion/timeout/error/send failure; the client reconnects.
 * <p>
 * Writes never run on the caller's thread. An {@code SseEmitter.send} is a blocking
 * socket write, and a client that stopped reading (a phone that lost signal without
 * closing the connection) blocks it until the TCP buffers give up. The heartbeat loop
 * wrote to every emitter in turn on one thread, so one such client stalled the
 * heartbeat - and dead-connection detection - for everybody, and a notification push
 * stalled the listener thread that sent it. Each write now runs on its own virtual
 * thread: a stuck one costs that connection nothing but itself, and a heartbeat that is
 * still stuck when the next one is due drops the connection.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final Map<UUID, List<Connection>> emittersByUser = new ConcurrentHashMap<>();
    private final long timeoutMillis;
    private final Duration heartbeatInterval;
    private final int maxPerUser;
    private final ExecutorService writers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sse-write-", 0).factory());
    private ScheduledExecutorService heartbeats;

    /**
     * @param maxPerUser open streams one account may hold. Every stream pins a servlet
     *                   request thread for up to {@code timeout}, and nothing capped them:
     *                   one account reconnecting in a loop (or a buggy client that never
     *                   closed the old stream) could hold as many threads as it liked.
     *                   A phone and a tablet or two is the real use; the oldest stream is
     *                   closed to make room, since the newest is the one being looked at.
     */
    SseEmitterRegistry(
            @Value("${linkup.sse.timeout:PT15M}") Duration timeout,
            @Value("${linkup.sse.heartbeat:PT30S}") Duration heartbeatInterval,
            @Value("${linkup.sse.max-per-user:3}") int maxPerUser) {
        this.timeoutMillis = timeout.toMillis();
        this.heartbeatInterval = heartbeatInterval;
        this.maxPerUser = Math.max(1, maxPerUser);
    }

    /**
     * One daemon thread, owned here rather than by app-wide {@code @EnableScheduling}.
     * It only hands writes to {@link #writers}, so it never blocks on a client.
     * Disabled by setting {@code linkup.sse.heartbeat} to zero.
     */
    @PostConstruct
    void startHeartbeats() {
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            return;
        }
        heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long millis = heartbeatInterval.toMillis();
        heartbeats.scheduleAtFixedRate(this::sendHeartbeats, millis, millis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
        writers.shutdownNow();
    }

    /**
     * Emitters get a finite timeout. {@code new SseEmitter(0L)} means "never time out",
     * and on the servlet stack every open connection holds a request thread - so a few
     * hundred idle clients (or clients that vanished without closing) were enough to
     * exhaust the pool. On timeout the client sees a normal disconnect and reconnects.
     * <p>
     * The comment sent here is what commits the response. Until something is written,
     * Spring MVC has sent nothing at all - not even the status line and headers - so
     * from the client's side an idle stream is indistinguishable from a server that
     * never answered. The Flutter client applies a 15s receive timeout to the request
     * that yields those headers, so on a quiet stream it timed out, reconnected, timed
     * out again, and live updates only ever arrived if a notification happened to fire
     * within 15s of connecting. A comment ({@code : ...}) is ignored by every SSE
     * parser, including the hand-rolled one in the client.
     * <p>
     * That first write stays on the request thread: the emitter isn't handed to Spring
     * MVC yet, so {@code send} only buffers it and cannot block.
     */
    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Connection connection = new Connection(emitter);

        List<Connection> evicted = new ArrayList<>();
        emittersByUser.compute(userId, (id, connections) -> {
            List<Connection> list = connections == null ? new CopyOnWriteArrayList<>() : connections;
            list.add(connection);
            while (list.size() > maxPerUser) {
                evicted.add(list.removeFirst());
            }
            return list;
        });
        // Outside compute(): complete() runs the emitter's own callbacks, which call
        // remove() - re-entering the map for the same key from inside compute() is not
        // allowed.
        evicted.forEach(old -> close(userId, old));

        Runnable cleanup = () -> remove(userId, connection);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        try {
            // Buffered by ResponseBodyEmitter until Spring MVC initialises the emitter,
            // then written immediately - so the headers reach the client on connect.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE connection for {} died before it opened", userId);
            remove(userId, connection);
        }

        return emitter;
    }

    /**
     * Keeps idle connections alive and, just as importantly, detects dead ones: a client
     * that vanished (backgrounded app, NAT timeout, flaky mobile network) is only
     * noticed when a write fails, so without traffic its emitter sat in this map holding
     * an open async request until the 15-minute timeout. A connection whose previous
     * heartbeat still hasn't been written a whole interval later is treated the same
     * way: it isn't reading, and waiting for its write to fail can take minutes.
     */
    private void sendHeartbeats() {
        emittersByUser.forEach((userId, connections) -> {
            for (Connection connection : connections) {
                if (!connection.heartbeatInFlight.compareAndSet(false, true)) {
                    log.debug("Dropping stalled SSE emitter for {}", userId);
                    close(userId, connection);
                    continue;
                }
                write(userId, connection, SseEmitter.event().comment("ping"),
                        () -> connection.heartbeatInFlight.set(false));
            }
        });
    }

    /** Fire-and-forget: returns before anything is written. */
    public void push(UUID userId, Object payload) {
        List<Connection> connections = emittersByUser.get(userId);
        if (connections == null) {
            return;
        }
        for (Connection connection : connections) {
            write(userId, connection, SseEmitter.event().name("notification").data(payload), () -> {});
        }
    }

    private void write(UUID userId, Connection connection, SseEmitter.SseEventBuilder event, Runnable after) {
        try {
            writers.execute(() -> {
                try {
                    connection.emitter.send(event);
                } catch (IOException | RuntimeException e) {
                    // IllegalStateException: the emitter completed before the write.
                    log.debug("Dropping dead SSE emitter for {}: {}", userId, e.toString());
                    remove(userId, connection);
                } finally {
                    after.run();
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutting down; the client will reconnect to whatever comes up next.
            after.run();
        }
    }

    /**
     * Forgets the connection first, so nothing else writes to it, then completes it off
     * the calling thread: completing a connection whose write is stuck can itself wait
     * on the same socket.
     */
    private void close(UUID userId, Connection connection) {
        remove(userId, connection);
        try {
            writers.execute(connection.emitter::complete);
        } catch (RejectedExecutionException e) {
            // Shutting down anyway.
        }
    }

    /**
     * Drops the per-user entry once its last emitter goes away. Leaving empty lists
     * behind meant the map grew by one entry per user who had ever connected, and never
     * shrank.
     */
    private void remove(UUID userId, Connection connection) {
        emittersByUser.computeIfPresent(userId, (id, connections) -> {
            connections.remove(connection);
            return connections.isEmpty() ? null : connections;
        });
    }

    /** Open streams for one user; for tests. */
    int connectionCount(UUID userId) {
        List<Connection> connections = emittersByUser.get(userId);
        return connections == null ? 0 : connections.size();
    }

    /** An emitter plus whether its last heartbeat has been written yet. */
    private static final class Connection {
        final SseEmitter emitter;
        final AtomicBoolean heartbeatInFlight = new AtomicBoolean();

        Connection(SseEmitter emitter) {
            this.emitter = emitter;
        }
    }
}
