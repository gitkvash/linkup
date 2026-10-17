package ge.kcamp.linkup.notification.fcm;

import ge.kcamp.linkup.notification.NotificationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationUrgencyTest {

    private static NotificationMessage of(String type, Map<String, String> metadata) {
        return new NotificationMessage(UUID.randomUUID(), type, "t", "b", metadata);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ACTIVITY_INVITE", "ACTIVITY_CANCELLED", "FRIEND_REQUEST",
            "ACTIVITY_REMINDER", "ACTIVITY_STARTING", "ACTIVITY_STARTED"})
    void thingsSomeoneMustActOnAreLoud(String type) {
        assertThat(NotificationUrgency.of(of(type, Map.of()))).isEqualTo(NotificationUrgency.LOUD);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "FRIEND_ACTIVITY", "GROUP_ACTIVITY", "INVITE_DECLINED", "TIME_PROPOSAL",
            "TIME_PROPOSAL_ACCEPTED", "TIME_PROPOSAL_DECLINED", "FRIEND_ACCEPTED", "GROUP_ADDED"})
    void newsThatCanWaitIsQuiet(String type) {
        assertThat(NotificationUrgency.of(of(type, Map.of()))).isEqualTo(NotificationUrgency.QUIET);
    }

    @Test
    void aNewTypeIsQuietUntilItAsksOtherwise() {
        assertThat(NotificationUrgency.of(of("SOMETHING_NEW", Map.of()))).isEqualTo(NotificationUrgency.QUIET);
    }

    @Test
    void anEditThatMovedTheTimeOrPlaceIsLoud() {
        NotificationMessage moved = of("ACTIVITY_UPDATED", Map.of(NotificationUrgency.URGENT_KEY, "true"));

        assertThat(NotificationUrgency.of(moved)).isEqualTo(NotificationUrgency.LOUD);
    }

    @Test
    void aRenameAloneIsQuiet() {
        NotificationMessage renamed = of("ACTIVITY_UPDATED", Map.of(NotificationUrgency.URGENT_KEY, "false"));

        assertThat(NotificationUrgency.of(renamed)).isEqualTo(NotificationUrgency.QUIET);
        assertThat(NotificationUrgency.of(of("ACTIVITY_UPDATED", Map.of()))).isEqualTo(NotificationUrgency.QUIET);
    }

    @Test
    void theChannelIdsMatchTheOnesTheAppCreates() {
        // MainActivity.kt creates these two; a push naming any other channel falls back
        // to the system's default and is neither loud nor quiet.
        assertThat(NotificationUrgency.LOUD.channelId()).isEqualTo("linkup_urgent");
        assertThat(NotificationUrgency.QUIET.channelId()).isEqualTo("linkup_updates");
    }
}
