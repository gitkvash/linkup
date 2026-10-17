package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.notification.CompositeNotificationDispatcher;
import ge.kcamp.linkup.notification.NotificationMessage;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.SocialGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FriendActivityNotificationTest {

    private static final long THRESHOLD = 500;

    private final UUID host = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID invited = UUID.randomUUID();
    private final UUID mutedHost = UUID.randomUUID();

    private CompositeNotificationDispatcher dispatcher;
    private SocialGraphService social;
    private ActivityEventListener listener;

    @BeforeEach
    void setUp() {
        dispatcher = mock(CompositeNotificationDispatcher.class);
        social = mock(SocialGraphService.class);
        UserDirectoryService directory = mock(UserDirectoryService.class);
        when(directory.findById(any())).thenReturn(Optional.empty());
        listener = new ActivityEventListener(
                dispatcher, directory, mock(GroupService.class), social, THRESHOLD, ZoneId.of("Asia/Tbilisi"));
        when(social.getAcceptedFriendIds(host)).thenReturn(List.of(friend, invited, mutedHost));
        when(social.getIdsWhoMuted(host)).thenReturn(Set.of(mutedHost));
    }

    private ActivityCreatedEvent event(ActivityVisibility visibility, UUID groupId) {
        return new ActivityCreatedEvent(UUID.randomUUID(), host, "Climbing",
                ZonedDateTime.now().plusDays(1), List.of(invited), groupId, visibility, Instant.now());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFriendsPlanReachesTheRestOfTheirFriendsQuietlyAndSkipsInviteesAndMutes() {
        listener.onFriendActivityCreated(event(ActivityVisibility.FRIENDS, null));

        ArgumentCaptor<List<NotificationMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(dispatcher).dispatchAll(sent.capture(), any());
        assertThat(sent.getValue()).singleElement().satisfies(message -> {
            assertThat(message.recipientUserId()).isEqualTo(friend);
            assertThat(message.type()).isEqualTo("FRIEND_ACTIVITY");
        });
    }

    @Test
    void aPrivateOrGroupPlanNotifiesNoFriend() {
        listener.onFriendActivityCreated(event(ActivityVisibility.PRIVATE, null));
        listener.onFriendActivityCreated(event(ActivityVisibility.GROUP, UUID.randomUUID()));

        verify(dispatcher, never()).dispatchAll(any(), any());
    }

    @Test
    void anInfluencersPlanIsLeftToTheFeed() {
        // Three friends against a threshold of three: past it, plans are pulled on read.
        ActivityEventListener small = new ActivityEventListener(
                dispatcher, mock(UserDirectoryService.class), mock(GroupService.class), social,
                3L, ZoneId.of("Asia/Tbilisi"));

        small.onFriendActivityCreated(event(ActivityVisibility.PUBLIC, null));

        verify(dispatcher, never()).dispatchAll(any(), any());
    }
}
