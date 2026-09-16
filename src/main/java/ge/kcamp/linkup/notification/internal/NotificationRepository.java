package ge.kcamp.linkup.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);

    boolean existsByDedupeKey(String dedupeKey);

    long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

    /**
     * The recipient is part of the predicate, not just the id: that makes it
     * impossible to mark someone else's notification as read, and lets the caller
     * distinguish "not yours / doesn't exist" from "already read" by the row count.
     */
    @Modifying
    @Query("""
            UPDATE Notification n SET n.readAt = :readAt
            WHERE n.id = :id AND n.recipientUserId = :recipientUserId AND n.readAt IS NULL
            """)
    int markRead(
            @Param("id") UUID id,
            @Param("recipientUserId") UUID recipientUserId,
            @Param("readAt") ZonedDateTime readAt);

    @Modifying
    @Query("""
            UPDATE Notification n SET n.readAt = :readAt
            WHERE n.recipientUserId = :recipientUserId AND n.readAt IS NULL
            """)
    int markAllRead(
            @Param("recipientUserId") UUID recipientUserId,
            @Param("readAt") ZonedDateTime readAt);
}
