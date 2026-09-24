package ge.kcamp.linkup.social;

import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.social.entity.Group;
import ge.kcamp.linkup.social.entity.GroupMember;
import ge.kcamp.linkup.social.exception.GroupMemberNotFriendException;
import ge.kcamp.linkup.social.exception.GroupNotOwnedException;
import ge.kcamp.linkup.social.repository.GroupMemberRepository;
import ge.kcamp.linkup.social.repository.GroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupServiceTest {

    private final UUID groupId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private GroupRepository groups;
    private GroupMemberRepository members;
    private SocialGraphService socialGraph;
    private GroupService service;

    @BeforeEach
    void setUp() {
        groups = mock(GroupRepository.class);
        members = mock(GroupMemberRepository.class);
        socialGraph = mock(SocialGraphService.class);
        service = new GroupService(groups, members, mock(UserDirectoryService.class), socialGraph);

        Group group = new Group();
        group.setId(groupId);
        group.setOwnerId(owner);
        when(groups.findById(groupId)).thenReturn(Optional.of(group));
        when(members.existsByIdGroupIdAndIdUserId(groupId, owner)).thenReturn(true);
        when(members.existsByIdGroupIdAndIdUserId(groupId, member)).thenReturn(true);
    }

    @Test
    void theOwnerCanAddAFriend() {
        when(socialGraph.areFriends(owner, member)).thenReturn(true);

        service.addMember(owner, groupId, member);

        ArgumentCaptor<GroupMember> saved = ArgumentCaptor.forClass(GroupMember.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getId().getUserId()).isEqualTo(member);
        assertThat(saved.getValue().getId().getGroupId()).isEqualTo(groupId);
    }

    @Test
    void theOwnerCannotAddSomeoneAcrossABlock() {
        when(socialGraph.isBlockedEitherWay(owner, stranger)).thenReturn(true);

        assertThatThrownBy(() -> service.addMember(owner, groupId, stranger))
                .isInstanceOf(GroupMemberNotFriendException.class);
        verify(members, never()).save(any());
    }

    @Test
    void aNonOwnerStillCannotAdd() {
        assertThatThrownBy(() -> service.addMember(member, groupId, stranger))
                .isInstanceOf(GroupNotOwnedException.class);
        verify(members, never()).save(any());
    }

    @Test
    void aMemberCanLeave() {
        service.removeMember(member, groupId, member);

        verify(members).deleteByIdGroupIdAndIdUserId(groupId, member);
    }

    @Test
    void theOwnerStillCannotLeaveTheirOwnGroup() {
        assertThatThrownBy(() -> service.removeMember(owner, groupId, owner))
                .isInstanceOf(IllegalArgumentException.class);
        verify(members, never()).deleteByIdGroupIdAndIdUserId(any(), any());
    }

    @Test
    void aMemberCannotRemoveSomeoneElse() {
        assertThatThrownBy(() -> service.removeMember(member, groupId, owner))
                .isInstanceOf(GroupNotOwnedException.class);
        verify(members, never()).deleteByIdGroupIdAndIdUserId(any(), any());
    }

    @Test
    void aNonMemberLeavingGetsTheSameAnswerAsAnyNonOwner() {
        assertThatThrownBy(() -> service.removeMember(stranger, groupId, stranger))
                .isInstanceOf(GroupNotOwnedException.class);
        verify(members, never()).deleteByIdGroupIdAndIdUserId(any(), any());
    }

    @Test
    void theOwnerCanStillRemoveAMember() {
        service.removeMember(owner, groupId, member);

        verify(members).deleteByIdGroupIdAndIdUserId(groupId, member);
    }
}
