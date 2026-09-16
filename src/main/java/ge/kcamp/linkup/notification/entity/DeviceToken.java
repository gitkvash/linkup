package ge.kcamp.linkup.notification.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.ZonedDateTime;

@Getter
@Setter
@Entity
@Table(name = "device_tokens")
public class DeviceToken {

    @EmbeddedId
    private DeviceTokenId id;

    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private ZonedDateTime updatedAt;
}
