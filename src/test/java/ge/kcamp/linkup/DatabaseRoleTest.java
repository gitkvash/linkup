package ge.kcamp.linkup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marker that decides which Postgres login a query runs under. Its default is the
 * whole security argument: code that says nothing gets the restricted role.
 */
class DatabaseRoleTest {

    @AfterEach
    void reset() {
        // The marker is a ThreadLocal and JUnit reuses threads.
        assertThat(DatabaseRole.current()).isEqualTo(DatabaseRole.APP);
    }

    @Test
    void defaultsToTheRestrictedRole() {
        assertThat(DatabaseRole.current()).isEqualTo(DatabaseRole.APP);
    }

    @Test
    void runAsSystemAppliesOnlyForTheDurationOfTheBody() {
        DatabaseRole.runAsSystem(() ->
                assertThat(DatabaseRole.current()).isEqualTo(DatabaseRole.SYSTEM));

        assertThat(DatabaseRole.current()).isEqualTo(DatabaseRole.APP);
    }

    @Test
    void nestingRestoresTheOuterRoleRatherThanClearingIt() {
        DatabaseRole.runAsSystem(() -> {
            DatabaseRole.runAsSystem(() ->
                    assertThat(DatabaseRole.current()).isEqualTo(DatabaseRole.SYSTEM));
            assertThat(DatabaseRole.current())
                    .as("the inner block must not drop the outer one back to APP")
                    .isEqualTo(DatabaseRole.SYSTEM);
        });
    }

    @Test
    void theMarkerIsClearedWhenTheBodyThrows() {
        assertThat(catchThrown(() -> DatabaseRole.runAsSystem(() -> {
            throw new IllegalStateException("boom");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(DatabaseRole.current())
                .as("a leaked SYSTEM marker would silently privilege every later request "
                        + "served by this thread")
                .isEqualTo(DatabaseRole.APP);
    }

    @Test
    void theMarkerDoesNotEscapeToOtherThreads() throws Exception {
        AtomicReference<DatabaseRole> seenOnOtherThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        DatabaseRole.runAsSystem(() -> {
            Thread other = new Thread(() -> {
                seenOnOtherThread.set(DatabaseRole.current());
                done.countDown();
            });
            other.start();
            try {
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        assertThat(seenOnOtherThread.get()).isEqualTo(DatabaseRole.APP);
    }

    private static Throwable catchThrown(Runnable body) {
        try {
            body.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
