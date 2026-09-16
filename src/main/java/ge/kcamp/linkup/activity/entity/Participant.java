package ge.kcamp.linkup.activity.entity;

import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Entity
@Table(name = "participants")
public class Participant {

    @EmbeddedId
    private ParticipantId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("activityId")
    @JoinColumn(name = "activity_id")
    private Activity activity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ParticipantStatus status;

    public Participant() {}

    public Participant(ParticipantId id, Activity activity, ParticipantStatus status) {
        this.id = id;
        this.activity = activity;
        this.status = status;
    }

    public static ParticipantBuilder builder() {
        return new ParticipantBuilder();
    }

    public static class ParticipantBuilder {
        private ParticipantId id;
        private Activity activity;
        private ParticipantStatus status;

        public ParticipantBuilder id(ParticipantId id) { this.id = id; return this; }
        public ParticipantBuilder activity(Activity activity) { this.activity = activity; return this; }
        public ParticipantBuilder status(ParticipantStatus status) { this.status = status; return this; }

        public Participant build() {
            return new Participant(id, activity, status);
        }
    }
}
