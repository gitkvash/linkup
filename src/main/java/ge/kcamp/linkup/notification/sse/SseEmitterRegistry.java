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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-user list of open SSE connections (multiple allowed, one per device/tab). The
 * registry cleans up on completion/timeout/error/send failure; the client reconnects.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final Map<UUID, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();
    private final long timeoutMillis;
    private final Duration heartbeatInterval;
    private ScheduledExecutorService heartbeats;

    SseEmitterRegistry(
            @Value("${linkup.sse.timeout:PT15M}") Duration timeout,
            @Value("${linkup.sse.heartbeat:PT30S}") Duration heartbeatInterval) {
        this.timeoutMillis = timeout.toMillis();
        this.heartbeatInterval = heartbeatInterval;
    }

    /**
     * One daemon thread, owned here rather than by app-wide {@code @EnableScheduling},
     * which nothing else in this application needs. Disabled by setting
     * {@code linkup.sse.heartbeat} to zero.
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
    void stopHeartbeats() {
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
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
     */
    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emittersByUser.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        try {
            // Buffered by ResponseBodyEmitter until Spring MVC initialises the emitter,
            // then written immediately - so the headers reach the client on connect.
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE connection for {} died before it opened", userId);
            remove(userId, emitter);
        }

        return emitter;
    }

    /**
     * Keeps idle connections alive and, just as importantly, detects dead ones: a client
     * that vanished (backgrounded app, NAT timeout, flaky mobile network) is only
     * noticed when a write fails, so without traffic its emitter sat in this map holding
     * an open async request until the 15-minute timeout.
     */
    private void sendHeartbeats() {
        emittersByUser.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Dropping dead SSE emitter for {}", userId);
                    remove(userId, emitter);
                }
            }
        });
    }

    public void push(UUID userId, Object payload) {
        List<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("notification").data(payload));
            } catch (IOException | IllegalStateException e) {
                // IllegalStateException: the emitter completed between get() and send().
                log.debug("Dropping dead SSE emitter for {}", userId);
                remove(userId, emitter);
            }
        }
    }

    /**
     * Drops the per-user entry once its last emitter goes away. Leaving empty lists
     * behind meant the map grew by one entry per user who had ever connected, and never
     * shrank.
     */
    private void remove(UUID userId, SseEmitter emitter) {
        emittersByUser.computeIfPresent(userId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
