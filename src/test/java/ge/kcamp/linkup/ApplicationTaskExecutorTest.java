package ge.kcamp.linkup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The executor every listener runs on, and the resubmitter that leans on it: both are
 * only correct if work lands on the system role.
 */
class ApplicationTaskExecutorTest {

    private final ThreadPoolTaskExecutor executor = new DataSourceConfig().applicationTaskExecutor();

    @AfterEach
    void shutDown() {
        executor.shutdown();
    }

    @Test
    void theSystemPoolHasAConnectionForEveryExecutorThread() {
        executor.initialize();

        assertThat(DataSourceConfig.EXECUTOR_MAX_THREADS + DataSourceConfig.SYSTEM_POOL_HEADROOM)
                .isGreaterThan(executor.getMaxPoolSize());
    }

    @Test
    void tasksRunAsTheSystemRole() throws InterruptedException {
        executor.initialize();
        AtomicReference<DatabaseRole> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            seen.set(DatabaseRole.current());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo(DatabaseRole.SYSTEM);
    }

    @Test
    void aSaturatedExecutorRefusesLoudlyInsteadOfDroppingTheTask() {
        executor.initialize();

        assertThatThrownBy(() -> executor.getThreadPoolExecutor().getRejectedExecutionHandler()
                .rejectedExecution(() -> { }, executor.getThreadPoolExecutor()))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void resubmissionRunsOnTheExecutorAsTheSystemRole() throws InterruptedException {
        executor.initialize();
        AtomicReference<DatabaseRole> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        IncompleteEventPublications publications = mock(IncompleteEventPublications.class);
        doAnswer(invocation -> {
            seen.set(DatabaseRole.current());
            done.countDown();
            return null;
        }).when(publications).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
        EventPublicationResubmitter resubmitter =
                new EventPublicationResubmitter(publications, executor, Duration.ofMinutes(5), Duration.ZERO);

        resubmitter.tick();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo(DatabaseRole.SYSTEM);
        assertThat(DatabaseRole.current()).as("the ticking thread is left alone").isEqualTo(DatabaseRole.APP);
    }

    @Test
    void aFailingResubmissionDoesNotBlockTheNextOne() {
        IncompleteEventPublications publications = mock(IncompleteEventPublications.class);
        doThrow(new IllegalStateException("db down"))
                .when(publications).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
        EventPublicationResubmitter resubmitter = new EventPublicationResubmitter(
                publications, Runnable::run, Duration.ofMinutes(5), Duration.ZERO);

        resubmitter.tick();
        resubmitter.tick();

        verify(publications, times(2))
                .resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
    }
}
