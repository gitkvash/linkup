package ge.kcamp.linkup.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@EqualsAndHashCode
@Embeddable
public class DeviceTokenId implements Serializable {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "fcm_token")
    private String fcmToken;

    public DeviceTokenId() {
    }

    public DeviceTokenId(UUID userId, String fcmToken) {
        this.userId = userId;
        this.fcmToken = fcmToken;
    }
}
