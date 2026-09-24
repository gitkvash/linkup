package ge.kcamp.linkup.social;

import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.social.entity.Friendship;
import ge.kcamp.linkup.social.entity.FriendshipId;
import ge.kcamp.linkup.social.enums.FriendshipStatus;
import ge.kcamp.linkup.social.exception.FriendRequestNotFoundException;
import ge.kcamp.linkup.social.repository.FriendshipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The block rules, which are about what one party can learn or undo about the other.
 * Ids are chosen so the canonical pair order is obvious: {@code LOW} sorts first.
 */
class SocialGraphServiceTest {

    private static final UUID LOW = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID HIGH = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private FriendshipRepository friendships;
    private ApplicationEventPublisher events;
    private SocialGraphService service;

    @BeforeEach
    void setUp() {
        friendships = mock(FriendshipRepository.class);
        events = mock(ApplicationEventPublisher.class);
        UserDirectoryService directory = mock(UserDirectoryService.class);
        when(directory.namesFor(anyCollection())).thenReturn(Map.of());
        service = new SocialGraphService(friendships, directory, events);
    }

    @Test
    void theBlockedPartyCannotTakeOverABlockAndThenLiftIt() {
        // HIGH blocked LOW. LOW blocks back, then tries to unblock.
        Friendship row = row(FriendshipStatus.BLOCKED, HIGH);
        when(friendships.findByIdUserAIdAndIdUserBId(LOW, HIGH)).thenReturn(Optional.of(row));

        service.blockUser(LOW, HIGH);

        assertThat(row.getRequestedBy()).as("the blocker of record").isEqualTo(HIGH);
        verify(friendships, never()).save(any());
        assertThatThrownBy(() -> service.unblockUser(LOW, HIGH))
                .isInstanceOf(FriendRequestNotFoundException.class);
        verify(friendships, never()).delete(any());
    }

    @Test
    void blockingAFriendEndsTheFriendship() {
        Friendship row = row(FriendshipStatus.ACCEPTED, LOW);
        when(friendships.findByIdUserAIdAndIdUserBId(LOW, HIGH)).thenReturn(Optional.of(row));

        service.blockUser(HIGH, LOW);

        assertThat(row.getStatus()).isEqualTo(FriendshipStatus.BLOCKED);
        assertThat(row.getRequestedBy()).isEqualTo(HIGH);
        FriendshipEndedEvent ended = captureOne(FriendshipEndedEvent.class);
        assertThat(List.of(ended.userAId(), ended.userBId())).containsExactly(LOW, HIGH);
    }

    @Test
    void blockingAStrangerPublishesNothing() {
        when(friendships.findByIdUserAIdAndIdUserBId(LOW, HIGH)).thenReturn(Optional.empty());

        service.blockUser(HIGH, LOW);

        verify(friendships).save(any(Friendship.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aRequestIntoABlockedPairLooksSentAndChangesNothing() {
        Friendship row = row(FriendshipStatus.BLOCKED, HIGH);
        when(friendships.findByIdUserAIdAndIdUserBId(LOW, HIGH)).thenReturn(Optional.of(row));

        // Neither direction may throw - a 403 here is what revealed the block.
        service.sendFriendRequest(LOW, HIGH);
        service.sendFriendRequest(HIGH, LOW);

        assertThat(row.getStatus()).isEqualTo(FriendshipStatus.BLOCKED);
        assertThat(row.getRequestedBy()).isEqualTo(HIGH);
        verify(friendships, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void unfriendingPublishesFriendshipEnded() {
        Friendship row = row(FriendshipStatus.ACCEPTED, LOW);
        when(friendships.findByIdUserAIdAndIdUserBId(LOW, HIGH)).thenReturn(Optional.of(row));

        service.unfriend(HIGH, LOW);

        verify(friendships).delete(row);
        FriendshipEndedEvent ended = captureOne(FriendshipEndedEvent.class);
        assertThat(List.of(ended.userAId(), ended.userBId())).containsExactly(LOW, HIGH);
    }

    @Test
    void areFriendsIsFalseForABlockedPair() {
        when(friendships.findByIdUserAIdAndIdUserBId(LOW, HIGH))
                .thenReturn(Optional.of(row(FriendshipStatus.BLOCKED, LOW)));

        assertThat(service.areFriends(HIGH, LOW)).isFalse();
    }

    @Test
    void areFriendsLooksUpTheCanonicalPairEitherWayRound() {
        when(friendships.findByIdUserAIdAndIdUserBId(LOW, HIGH))
                .thenReturn(Optional.of(row(FriendshipStatus.ACCEPTED, LOW)));

        assertThat(service.areFriends(HIGH, LOW)).isTrue();
        assertThat(service.areFriends(LOW, HIGH)).isTrue();
    }

    private static Friendship row(FriendshipStatus status, UUID requestedBy) {
        FriendshipId id = new FriendshipId();
        id.setUserAId(LOW);
        id.setUserBId(HIGH);
        Friendship friendship = new Friendship();
        friendship.setId(id);
        friendship.setStatus(status);
        friendship.setRequestedBy(requestedBy);
        return friendship;
    }

    private <T> T captureOne(Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(type);
        return type.cast(captor.getValue());
    }
}
