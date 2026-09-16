package ge.kcamp.linkup.activity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Setter
@Getter
@Embeddable
public class ParticipantId implements Serializable {

    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "user_id")
    private UUID userId;

    public ParticipantId() {}

    public ParticipantId(UUID activityId, UUID userId) {
        this.activityId = activityId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParticipantId that = (ParticipantId) o;
        return Objects.equals(activityId, that.activityId) &&
                Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(activityId, userId);
    }
}
