package ge.kcamp.linkup.notification.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.notification.internal.Notification;
import ge.kcamp.linkup.notification.internal.NotificationRepository;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * The caller's most recent notifications, newest first. {@code limit} defaults to 50
     * and is clamped to 1..100 rather than rejected, so the shipped client - which sends
     * no parameter - keeps working unchanged. There was no bound at all: the whole
     * history came back on every open of the Alerts tab, growing for as long as the
     * account existed.
     */
    @GetMapping
    public List<Notification> myNotifications(
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(
                UserContext.getUserId(), Limit.of(clamp(limit)));
    }

    static int clamp(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
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
