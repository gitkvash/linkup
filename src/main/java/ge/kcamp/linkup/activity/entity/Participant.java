package ge.kcamp.linkup.activity.entity;

import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * {@link Persistable} because the id is assigned, not generated - as on
 * {@code RefreshToken}. Without it Spring Data sees a non-null id, assumes the row exists
 * and merges: a SELECT before every INSERT, once per invitee when a plan is created.
 * Status changes don't go through here ({@code ParticipantRepository.upsertStatus}); a
 * row read back from the database is marked persisted on load, so saving it merges.
 */
@Setter
@Getter
@Entity
@Table(name = "participants")
public class Participant implements Persistable<ParticipantId> {

    @EmbeddedId
    private ParticipantId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("activityId")
    @JoinColumn(name = "activity_id")
    private Activity activity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ParticipantStatus status;

    /** Why they said no, in their words. Only ever set while {@code status} is DECLINED. */
    @Column(name = "decline_reason", length = 200)
    private String declineReason;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean fresh = true;

    public Participant() {}

    public Participant(ParticipantId id, Activity activity, ParticipantStatus status) {
        this.id = id;
        this.activity = activity;
        this.status = status;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        fresh = false;
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
