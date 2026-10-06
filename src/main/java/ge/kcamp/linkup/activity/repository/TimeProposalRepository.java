package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.activity.entity.TimeProposal;
import ge.kcamp.linkup.activity.enums.TimeProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TimeProposalRepository extends JpaRepository<TimeProposal, UUID> {

    List<TimeProposal> findByActivityIdAndStatusOrderByCreatedAtAsc(UUID activityId, TimeProposalStatus status);

    List<TimeProposal> findByActivityIdAndProposerIdAndStatus(
            UUID activityId, UUID proposerId, TimeProposalStatus status);

    Optional<TimeProposal> findByIdAndActivityId(UUID id, UUID activityId);
}
