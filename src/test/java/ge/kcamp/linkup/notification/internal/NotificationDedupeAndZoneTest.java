package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.notification.CompositeNotificationDispatcher;
import ge.kcamp.linkup.notification.NotificationMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NotificationDedupeAndZoneTest {

    private static final UUID RECIPIENT = UUID.fromString("0c9d2a3e-6d0e-4a7b-9c55-1f0e9d8c7b6a");
    private static final UUID OTHER = UUID.fromString("7f3b1c2d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

    private static NotificationMessage request() {
        return new NotificationMessage(RECIPIENT, "FRIEND_REQUEST", "t", "b",
                Map.of("otherUserId", OTHER.toString()));
    }

    @Test
    void aRedeliveryOfTheSameEventHasTheSameKey() {
        Instant occurredAt = Instant.parse("2026-09-24T10:15:30.123456789Z");

        assertThat(LoggingNotificationDispatcher.dedupeKeyFor(request(), occurredAt))
                .isEqualTo(LoggingNotificationDispatcher.dedupeKeyFor(request(), occurredAt));
    }

    @Test
    void aRedeliveryThatLostSubMillisecondPrecisionStillDedupes() {
        Instant original = Instant.parse("2026-09-24T10:15:30.123456789Z");
        Instant roundTripped = Instant.parse("2026-09-24T10:15:30.123Z");

        assertThat(LoggingNotificationDispatcher.dedupeKeyFor(request(), roundTripped))
                .isEqualTo(LoggingNotificationDispatcher.dedupeKeyFor(request(), original));
    }

    @Test
    void aLaterEventAboutTheSameSubjectIsNotSuppressed() {
        // Decline, then the same person asks again: the second request must get through.
        Instant first = Instant.parse("2026-09-24T10:15:30Z");
        Instant second = Instant.parse("2026-09-25T08:00:00Z");

        assertThat(LoggingNotificationDispatcher.dedupeKeyFor(request(), second))
                .isNotEqualTo(LoggingNotificationDispatcher.dedupeKeyFor(request(), first));
    }

    @Test
    void theKeyFitsTheColumn() {
        NotificationMessage longest = new NotificationMessage(RECIPIENT, "ACTIVITY_INVITE", "t", "b",
                Map.of("activityId", UUID.randomUUID().toString(), "otherUserId", OTHER.toString()));

        // notifications.dedupe_key is VARCHAR(200) (V10).
        assertThat(LoggingNotificationDispatcher.dedupeKeyFor(longest, Instant.parse("9999-12-31T23:59:59.999Z")))
                .hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void aMessageWithNoSubjectIsNotDeduplicated() {
        NotificationMessage bare = new NotificationMessage(RECIPIENT, "X", "t", "b", Map.of());

        assertThat(LoggingNotificationDispatcher.dedupeKeyFor(bare, Instant.now())).isNull();
    }

    @Test
    void planTimesAreFormattedInTheConfiguredZoneNotTheJvms() {
        ActivityEventListener listener = new ActivityEventListener(
                mock(CompositeNotificationDispatcher.class), mock(UserDirectoryService.class),
                ZoneId.of("Asia/Tbilisi"));
        // 03:00 UTC, stored with a UTC offset - what the server holds on Render. Picked
        // so the check reads the same in 12- and 24-hour locales.
        ZonedDateTime utc = ZonedDateTime.of(2026, 9, 24, 3, 0, 0, 0, ZoneOffset.UTC);

        String formatted = listener.formatWhen(utc);

        // Tbilisi is UTC+4 all year.
        assertThat(formatted).contains("7:00").doesNotContain("3:00");
    }
}
