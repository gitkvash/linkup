package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.activity.ActivityChangedEvent;
import ge.kcamp.linkup.activity.ActivityParticipationService;
import ge.kcamp.linkup.feed.FeedTimelinesUpdatedEvent;
import ge.kcamp.linkup.notification.sse.SseEmitterRegistry;
import ge.kcamp.linkup.notification.sse.SyncSignal;
import ge.kcamp.linkup.social.FriendRequestDeclinedEvent;
import ge.kcamp.linkup.social.FriendshipEndedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A change with no notification of its own still reaches the people it changes. */
class SyncSignalListenerTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID activityId = UUID.randomUUID();

    private SseEmitterRegistry registry;
    private ActivityParticipationService participation;
    private SyncSignalListener listener;

    @BeforeEach
    void setUp() {
        registry = mock(SseEmitterRegistry.class);
        participation = mock(ActivityParticipationService.class);
        listener = new SyncSignalListener(registry, participation);
    }

    @Test
    void aFriendsNewPlanTellsEveryTimelineItReachedToReReadTheFeed() {
        listener.onFeedTimelinesUpdated(new FeedTimelinesUpdatedEvent(Set.of(alice, bob), Instant.now()));

        ArgumentCaptor<SyncSignal> sent = ArgumentCaptor.forClass(SyncSignal.class);
        verify(registry).pushSync(eq(alice), sent.capture());
        verify(registry).pushSync(eq(bob), any(SyncSignal.class));
        assertThat(sent.getValue().topics()).containsExactly(SyncSignal.FEED, SyncSignal.MAP);
        assertThat(sent.getValue().activityId()).isNull();
    }

    @Test
    void aChangeToAPlanReachesTheHostAndEveryoneInItWithThePlansId() {
        when(participation.audienceOf(activityId)).thenReturn(Set.of(alice, bob));

        listener.onActivityChanged(new ActivityChangedEvent(activityId, Instant.now()));

        ArgumentCaptor<SyncSignal> sent = ArgumentCaptor.forClass(SyncSignal.class);
        verify(registry).pushSync(eq(alice), sent.capture());
        verify(registry).pushSync(eq(bob), any(SyncSignal.class));
        assertThat(sent.getValue().topics()).contains(SyncSignal.PLANS);
        assertThat(sent.getValue().activityId()).isEqualTo(activityId.toString());
    }

    @Test
    void anEndedFriendshipTellsBothSidesToReReadFriendsRequestsAndFeed() {
        listener.onFriendshipEnded(new FriendshipEndedEvent(alice, bob, Instant.now()));

        ArgumentCaptor<SyncSignal> sent = ArgumentCaptor.forClass(SyncSignal.class);
        verify(registry).pushSync(eq(alice), sent.capture());
        verify(registry).pushSync(eq(bob), any(SyncSignal.class));
        assertThat(sent.getValue().topics())
                .contains(SyncSignal.FRIENDS, SyncSignal.REQUESTS, SyncSignal.FEED);
    }

    @Test
    void aDeclinedRequestTellsOnlyTheRequesterAndNotTheDecliner() {
        listener.onFriendRequestDeclined(new FriendRequestDeclinedEvent(alice, bob, Instant.now()));

        ArgumentCaptor<SyncSignal> sent = ArgumentCaptor.forClass(SyncSignal.class);
        verify(registry).pushSync(eq(alice), sent.capture());
        verify(registry, never()).pushSync(eq(bob), any(SyncSignal.class));
        assertThat(sent.getValue().topics()).containsExactly(SyncSignal.REQUESTS);
    }
}
