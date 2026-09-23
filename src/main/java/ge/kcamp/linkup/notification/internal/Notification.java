package ge.kcamp.linkup.notification.internal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.ZonedDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "notification_id")
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false)
    private UUID recipientUserId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "created_at", insertable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "read_at")
    private ZonedDateTime readAt;

    /**
     * Stable identity for one logical notification, so a redelivered event can't create
     * a second row. Null when the message carries nothing to key on, which opts that row
     * out of deduplication.
     */
    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    /**
     * The plan this is about, when it is about one (V27). Lets a stored row open the
     * plan - and an invitation be answered from the Alerts list - instead of only
     * pointing at a tab. Not a foreign key: a cancelled plan leaves the id behind, and
     * the client already treats an id that 404s as "this plan is gone".
     */
    @Column(name = "activity_id")
    private UUID activityId;
}
