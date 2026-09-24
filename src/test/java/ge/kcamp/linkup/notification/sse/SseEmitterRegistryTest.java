package ge.kcamp.linkup.notification.sse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterRegistryTest {

    private final SseEmitterRegistry registry =
            new SseEmitterRegistry(Duration.ofMinutes(15), Duration.ZERO, 3);

    @AfterEach
    void tearDown() {
        registry.stop();
    }

    @Test
    void oneUserCannotHoldMoreThanTheCap() {
        UUID user = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            registry.register(user);
        }

        assertThat(registry.connectionCount(user)).isEqualTo(3);
    }

    @Test
    void theCapIsPerUser() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            registry.register(first);
            registry.register(second);
        }

        assertThat(registry.connectionCount(first)).isEqualTo(3);
        assertThat(registry.connectionCount(second)).isEqualTo(3);
    }

    @Test
    void pushingToAUserWithNoStreamIsANoOp() {
        registry.push(UUID.randomUUID(), "payload");
    }
}
