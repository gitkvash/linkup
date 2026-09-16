package ge.kcamp.linkup.social.repository;

import ge.kcamp.linkup.social.entity.GroupMember;
import ge.kcamp.linkup.social.entity.GroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    List<GroupMember> findByIdGroupId(UUID groupId);

    List<GroupMember> findByIdUserId(UUID userId);

    /** One membership by primary key. Cheaper than scanning the whole roster to answer it. */
    boolean existsByIdGroupIdAndIdUserId(UUID groupId, UUID userId);

    void deleteByIdGroupIdAndIdUserId(UUID groupId, UUID userId);
}
