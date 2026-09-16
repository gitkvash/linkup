package ge.kcamp.linkup.notification;

import java.util.Map;
import java.util.UUID;

public record NotificationMessage(
        UUID recipientUserId,
        String type,
        String title,
        String body,
        Map<String, String> metadata
) {
}
