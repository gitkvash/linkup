package ge.kcamp.linkup.notification.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.notification.internal.Notification;
import ge.kcamp.linkup.notification.internal.NotificationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    public List<Notification> myNotifications() {
        return notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(UserContext.getUserId());
    }

    /**
     * How many are unread. {@code read_at} existed on the table and in the entity but
     * nothing ever wrote it, so the client had to approximate this with a locally
     * stored "last opened" timestamp - per-device, and lost on sign-out.
     */
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of(
                "unread",
                notificationRepository.countByRecipientUserIdAndReadAtIsNull(UserContext.getUserId()));
    }

    /**
     * Scoped to the caller's own rows: the update is keyed on
     * {@code (id, recipient_user_id)}, so passing someone else's notification id
     * marks nothing and returns 404 rather than silently mutating their data.
     */
    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        int updated = notificationRepository.markRead(
                id, UserContext.getUserId(), ZonedDateTime.now());
        return updated == 0
                ? ResponseEntity.notFound().build()
                : ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Transactional
    public Map<String, Integer> markAllRead() {
        return Map.of(
                "marked",
                notificationRepository.markAllRead(UserContext.getUserId(), ZonedDateTime.now()));
    }
}
