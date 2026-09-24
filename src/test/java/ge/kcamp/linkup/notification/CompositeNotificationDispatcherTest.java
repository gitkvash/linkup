package ge.kcamp.linkup.notification;

import ge.kcamp.linkup.notification.fcm.FcmNotificationDispatcher;
import ge.kcamp.linkup.notification.internal.LoggingNotificationDispatcher;
import ge.kcamp.linkup.notification.sse.SseNotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The ordering and failure rules the listener's retry semantics depend on. */
class CompositeNotificationDispatcherTest {

    private final NotificationMessage message = new NotificationMessage(
            UUID.randomUUID(), "FRIEND_REQUEST", "Nino wants to be friends", "Accept or decline",
            Map.of("otherUserId", UUID.randomUUID().toString()));
    private final Instant occurredAt = Instant.parse("2026-09-24T10:15:30.123456Z");

    private LoggingNotificationDispatcher store;
    private FcmNotificationDispatcher fcm;
    private SseNotificationDispatcher sse;
    private CompositeNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        store = mock(LoggingNotificationDispatcher.class);
        fcm = mock(FcmNotificationDispatcher.class);
        sse = mock(SseNotificationDispatcher.class);
        dispatcher = new CompositeNotificationDispatcher(store, fcm, sse);
    }

    @Test
    void aNewNotificationIsPushedOnEveryChannel() {
        when(store.record(message, occurredAt)).thenReturn(true);

        dispatcher.dispatch(message, occurredAt);

        verify(fcm).dispatch(message);
        verify(sse).dispatch(message);
    }

    @Test
    void aDuplicateIsNotPushedAgain() {
        when(store.record(message, occurredAt)).thenReturn(false);

        dispatcher.dispatch(message, occurredAt);

        verify(fcm, never()).dispatch(any());
        verify(sse, never()).dispatch(any());
    }

    @Test
    void aFailedInsertPropagatesSoThePublicationIsRetried() {
        when(store.record(message, occurredAt))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        assertThatThrownBy(() -> dispatcher.dispatch(message, occurredAt))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(fcm, never()).dispatch(any());
        verify(sse, never()).dispatch(any());
    }

    @Test
    void aFailedPushIsBestEffortAndDoesNotStopTheNextChannel() {
        when(store.record(message, occurredAt)).thenReturn(true);
        doThrow(new IllegalStateException("FCM down")).when(fcm).dispatch(message);

        dispatcher.dispatch(message, occurredAt);

        verify(sse).dispatch(message);
    }
}
