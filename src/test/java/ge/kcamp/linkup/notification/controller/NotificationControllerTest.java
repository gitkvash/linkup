package ge.kcamp.linkup.notification.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.notification.internal.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class NotificationControllerTest {

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    @Test
    void theLimitIsClampedRatherThanRejected() {
        assertThat(NotificationController.clamp(0)).isEqualTo(1);
        assertThat(NotificationController.clamp(-5)).isEqualTo(1);
        assertThat(NotificationController.clamp(50)).isEqualTo(50);
        assertThat(NotificationController.clamp(10_000)).isEqualTo(NotificationController.MAX_LIMIT);
    }

    @Test
    void theCallersOwnRowsAreReadWithTheLimit() {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(any(), any())).thenReturn(List.of());
        UUID caller = UUID.randomUUID();
        UserContext.setUserId(caller);

        new NotificationController(repository).myNotifications(NotificationController.DEFAULT_LIMIT);

        verify(repository).findByRecipientUserIdOrderByCreatedAtDescIdDesc(
                eq(caller), eq(Limit.of(NotificationController.DEFAULT_LIMIT)));
    }
}
