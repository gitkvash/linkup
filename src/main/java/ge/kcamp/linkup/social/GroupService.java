package ge.kcamp.linkup.social;

import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.social.entity.Group;
import ge.kcamp.linkup.social.entity.GroupMember;
import ge.kcamp.linkup.social.entity.GroupMemberId;
import ge.kcamp.linkup.social.exception.GroupMemberNotFriendException;
import ge.kcamp.linkup.social.exception.GroupNotOwnedException;
import ge.kcamp.linkup.social.repository.GroupMemberRepository;
import ge.kcamp.linkup.social.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserDirectoryService userDirectoryService;
    private final SocialGraphService socialGraphService;

    public GroupService(
            GroupRepository groupRepository,
            GroupMemberRepository groupMemberRepository,
            UserDirectoryService userDirectoryService,
            SocialGraphService socialGraphService) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userDirectoryService = userDirectoryService;
        this.socialGraphService = socialGraphService;
    }

    @Transactional
    public Group createGroup(UUID ownerId, String groupName) {
        Group group = new Group();
        group.setOwnerId(ownerId);
        group.setGroupName(groupName);
        Group saved = groupRepository.save(group);

        GroupMember ownerMembership = new GroupMember();
        ownerMembership.setId(memberId(saved.getId(), ownerId));
        groupMemberRepository.save(ownerMembership);

        return saved;
    }

    /**
     * Anyone can be added except across a block, in either direction. A group is how
     * people who are not all friends plan together (see {@code ActivityCreatedEvent}),
     * so friendship is deliberately not required - but any user id used to be accepted,
     * so someone who had been blocked could put the person who blocked them into a
     * group and reach their feed and map with every GROUP plan. The way out of an
     * unwanted group is leaving it ({@link #removeMember}).
     * <p>
     * A blocked pair is refused with the same answer whichever side placed the block,
     * so this can't be used to learn who blocked whom.
     */
    @Transactional
    public void addMember(UUID requesterId, UUID groupId, UUID userId) {
        Group group = requireOwner(requesterId, groupId);
        if (group.getOwnerId().equals(userId)) {
            // Already a member since createGroup; re-adding would only rewrite joined_at.
            return;
        }
        if (socialGraphService.isBlockedEitherWay(requesterId, userId)) {
            throw new GroupMemberNotFriendException();
        }
        GroupMember member = new GroupMember();
        member.setId(memberId(groupId, userId));
        groupMemberRepository.save(member);
    }

    /**
     * The owner removes anyone; a member removes themselves (leaving). The owner cannot
     * be removed, including by themselves.
     * <p>
     * Leaving used to be impossible: this required ownership outright, so the only way
     * out of a group was to ask its owner - even though {@code group_members_delete_policy}
     * (V16) has always allowed {@code user_id = app_current_user()}. Someone else's
     * membership still needs the owner, and a non-member asking to remove themselves gets
     * the same 403 as any other non-owner, so it doesn't confirm the group exists.
     * <p>
     * Every read here is gated on <em>membership</em> while every write is gated on
     * <em>ownership</em>, so an owner who dropped their own membership kept the right to
     * change the group and lost the ability to see it: it left {@code /groups/mine},
     * {@code /groups/{id}} answered 403, and since the client only reaches a group
     * through that list there was no way back to it. There is no ownership transfer, so
     * the rule the client already assumes ("the owner can remove anyone but themselves")
     * is enforced here rather than trusted, the same way a plan's creator is stopped from
     * leaving their own plan in {@code ActivityParticipationService.leave}.
     */
    @Transactional
    public void removeMember(UUID requesterId, UUID groupId, UUID userId) {
        Group group = requesterId.equals(userId)
                ? requireMemberGroup(requesterId, groupId)
                : requireOwner(requesterId, groupId);
        if (group.getOwnerId().equals(userId)) {
            throw new IllegalArgumentException(
                    "You own this group, so you can't remove yourself from it.");
        }
        groupMemberRepository.deleteByIdGroupIdAndIdUserId(groupId, userId);
    }

    @Transactional(readOnly = true)
    public List<Group> listMyGroups(UUID userId) {
        // One IN query rather than a findById per membership row.
        List<UUID> groupIds = groupMemberRepository.findByIdUserId(userId).stream()
                .map(membership -> membership.getId().getGroupId())
                .toList();
        return groupIds.isEmpty() ? List.of() : groupRepository.findAllById(groupIds);
    }

    /**
     * One group, if the caller is a member. There was no way to fetch a group by id,
     * so the detail screen had to be handed the group through navigation state and
     * fell back to showing a UUID when that was lost.
     */
    @Transactional(readOnly = true)
    public Group getGroup(UUID requesterId, UUID groupId) {
        requireMember(requesterId, groupId);
        return groupRepository.findById(groupId).orElseThrow(GroupNotOwnedException::new);
    }

    /**
     * Members with their usernames. {@code findByIdGroupId} already existed on the
     * repository and was never called - the screen told the user the endpoint was
     * missing instead.
     */
    @Transactional(readOnly = true)
    public List<GroupMemberSummary> listMembers(UUID requesterId, UUID groupId) {
        requireMember(requesterId, groupId);

        Group group = groupRepository.findById(groupId).orElseThrow(GroupNotOwnedException::new);
        List<UUID> memberIds = groupMemberRepository.findByIdGroupId(groupId).stream()
                .map(membership -> membership.getId().getUserId())
                .toList();
        Map<UUID, String> usernames = userDirectoryService.namesFor(memberIds);

        return memberIds.stream()
                .map(id -> new GroupMemberSummary(
                        id, usernames.get(id), id.equals(group.getOwnerId())))
                // Owner first, then alphabetically.
                .sorted(Comparator
                        .comparing(GroupMemberSummary::owner).reversed()
                        .thenComparing(member -> member.username() == null
                                ? ""
                                : member.username().toLowerCase()))
                .toList();
    }

    /**
     * Part of this module's public API: {@code activity} needs it to check that a plan
     * being shared with a group is being shared by somebody in that group.
     *
     * <p>Returns false rather than throwing, so the caller can phrase its own error -
     * "no such group" and "you are not in it" have to be indistinguishable from outside,
     * or this becomes a way to discover which group ids exist.
     */
    @Transactional(readOnly = true)
    public boolean isMember(UUID groupId, UUID userId) {
        if (groupId == null || userId == null) {
            return false;
        }
        return groupMemberRepository.existsByIdGroupIdAndIdUserId(groupId, userId);
    }

    /**
     * Everyone in a group, for {@code feed} to fan a group plan out to. No membership
     * check: the caller is background work with no user acting, and the id it was handed
     * came from an activity that was itself authorized at creation.
     */
    @Transactional(readOnly = true)
    public List<UUID> memberIds(UUID groupId) {
        if (groupId == null) {
            return List.of();
        }
        return groupMemberRepository.findByIdGroupId(groupId).stream()
                .map(membership -> membership.getId().getUserId())
                .toList();
    }

    /** Any member may read; only the owner may change membership, bar leaving. */
    private void requireMember(UUID requesterId, UUID groupId) {
        if (!isMember(groupId, requesterId)) {
            throw new GroupNotOwnedException();
        }
    }

    private Group requireMemberGroup(UUID requesterId, UUID groupId) {
        requireMember(requesterId, groupId);
        return groupRepository.findById(groupId).orElseThrow(GroupNotOwnedException::new);
    }

    private Group requireOwner(UUID requesterId, UUID groupId) {
        Group group = groupRepository.findById(groupId).orElseThrow(GroupNotOwnedException::new);
        if (!group.getOwnerId().equals(requesterId)) {
            throw new GroupNotOwnedException();
        }
        return group;
    }

    private GroupMemberId memberId(UUID groupId, UUID userId) {
        GroupMemberId id = new GroupMemberId();
        id.setGroupId(groupId);
        id.setUserId(userId);
        return id;
    }
}
