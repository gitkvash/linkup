package ge.kcamp.linkup.social.repository;

import ge.kcamp.linkup.social.entity.GroupMember;
import ge.kcamp.linkup.social.entity.GroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    List<GroupMember> findByIdGroupId(UUID groupId);

    List<GroupMember> findByIdUserId(UUID userId);

    /** One membership by primary key. Cheaper than scanning the whole roster to answer it. */
    boolean existsByIdGroupIdAndIdUserId(UUID groupId, UUID userId);

    void deleteByIdGroupIdAndIdUserId(UUID groupId, UUID userId);

    /**
     * Groups both users are in, as (group id, name, member count), by name. Correct on a
     * request connection without any help: {@code group_members_select_policy} lets a
     * member read the whole roster of their own groups, and every group here is one of
     * the caller's.
     */
    @Query(value = """
            SELECT g.group_id, g.group_name,
                   (SELECT count(*) FROM group_members n WHERE n.group_id = g.group_id)
            FROM groups g
            JOIN group_members mine ON mine.group_id = g.group_id AND mine.user_id = :userId
            JOIN group_members theirs ON theirs.group_id = g.group_id AND theirs.user_id = :otherId
            ORDER BY lower(g.group_name)
            """, nativeQuery = true)
    List<Object[]> findGroupsInCommon(@Param("userId") UUID userId, @Param("otherId") UUID otherId);
}
