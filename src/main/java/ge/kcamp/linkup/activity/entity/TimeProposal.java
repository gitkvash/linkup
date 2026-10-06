package ge.kcamp.linkup.activity.entity;

import ge.kcamp.linkup.activity.enums.TimeProposalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.ZonedDateTime;
import java.util.UUID;

/** One person's suggestion that a plan happen at another time (V39). */
@Setter
@Getter
@Entity
@Table(name = "time_proposals")
public class TimeProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "proposal_id")
    private UUID id;

    @Column(name = "activity_id", nullable = false)
    private UUID activityId;

    @Column(name = "proposer_id", nullable = false)
    private UUID proposerId;

    @Column(name = "proposed_start_time", nullable = false)
    private ZonedDateTime proposedStartTime;

    @Column(name = "proposed_end_time")
    private ZonedDateTime proposedEndTime;

    @Column(name = "message", length = 200)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TimeProposalStatus status = TimeProposalStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now();

    @Column(name = "resolved_at")
    private ZonedDateTime resolvedAt;
}
