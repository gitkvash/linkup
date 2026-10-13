package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.activity.ActivityDeletedEvent;
import ge.kcamp.linkup.activity.ActivityUpdatedEvent;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.social.FriendshipAcceptedEvent;
import ge.kcamp.linkup.social.FriendshipEndedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The people whose feed changed are told only after the change is written. */
class FeedFanOutEventListenerTest {

    private final UUID creator = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID activityId = UUID.randomUUID();

    private FeedFanOutService fanOut;
    private ApplicationEventPublisher events;
    private FeedFanOutEventListener listener;

    @BeforeEach
    void setUp() {
        fanOut = mock(FeedFanOutService.class);
        events = mock(ApplicationEventPublisher.class);
        listener = new FeedFanOutEventListener(fanOut, mock(RedisFeedTimelineRepository.class), events);
    }

    @Test
    void aNewPlanTellsTheTimelinesItReachedAfterTheFanOut() {
        ZonedDateTime start = ZonedDateTime.now().plusDays(1);
        when(fanOut.fanOutOnWrite(creator, activityId, start.toInstant(), ActivityVisibility.FRIENDS, null, List.of()))
                .thenReturn(Set.of(creator, friend));

        listener.onActivityCreated(new ActivityCreatedEvent(
                activityId, creator, "Dinner", start, List.of(), null, ActivityVisibility.FRIENDS, Instant.now()));

        var order = inOrder(fanOut, events);
        order.verify(fanOut).fanOutOnWrite(
                creator, activityId, start.toInstant(), ActivityVisibility.FRIENDS, null, List.of());
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        order.verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(FeedTimelinesUpdatedEvent.class,
                event -> assertThat(event.userIds()).containsExactlyInAnyOrder(creator, friend));
    }

    /** The host and the people in the plan hear about an edit from notification; the rest from here. */
    @Test
    void anEditRescoresAndSignalsOnlyTheAudienceOutsideThePlan() {
        UUID invitee = UUID.randomUUID();
        ZonedDateTime start = ZonedDateTime.now().plusDays(2);
        when(fanOut.fanOutOnWrite(creator, activityId, start.toInstant(),
                ActivityVisibility.FRIENDS, null, List.of(invitee)))
                .thenReturn(Set.of(creator, invitee, friend));

        listener.onActivityUpdated(new ActivityUpdatedEvent(
                activityId, creator, start, null, ActivityVisibility.FRIENDS, List.of(invitee), Instant.now()));

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(FeedTimelinesUpdatedEvent.class,
                event -> assertThat(event.userIds()).containsExactly(friend));
    }

    @Test
    void anEditOfAPlanOnlyItsPeopleCanSeeSignalsNobodyFromHere() {
        UUID invitee = UUID.randomUUID();
        ZonedDateTime start = ZonedDateTime.now().plusDays(2);
        when(fanOut.fanOutOnWrite(creator, activityId, start.toInstant(),
                ActivityVisibility.PRIVATE, null, List.of(invitee)))
                .thenReturn(Set.of(creator, invitee));

        listener.onActivityUpdated(new ActivityUpdatedEvent(
                activityId, creator, start, null, ActivityVisibility.PRIVATE, List.of(invitee), Instant.now()));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aDeletedPlanLeavesEveryTimelineAndTellsAllButTheHost() {
        UUID groupId = UUID.randomUUID();
        when(fanOut.removeEverywhere(creator, activityId, ActivityVisibility.GROUP, groupId, List.of()))
                .thenReturn(Set.of(creator, friend));

        listener.onActivityDeleted(new ActivityDeletedEvent(
                activityId, creator, ActivityVisibility.GROUP, groupId, List.of(), Instant.now()));

        var order = inOrder(fanOut, events);
        order.verify(fanOut).removeEverywhere(creator, activityId, ActivityVisibility.GROUP, groupId, List.of());
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        order.verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(FeedTimelinesUpdatedEvent.class,
                event -> assertThat(event.userIds()).containsExactly(friend));
    }

    @Test
    void anEndedFriendshipPrunesBothTimelines() {
        listener.onFriendshipEnded(new FriendshipEndedEvent(creator, friend, Instant.now()));

        verify(fanOut).pruneInvisible(creator);
        verify(fanOut).pruneInvisible(friend);
    }

    @Test
    void aNewFriendshipTellsOnlyTheSidesThatGainedAPlan() {
        when(fanOut.backfillNewFriendship(creator, friend)).thenReturn(Set.of(friend));

        listener.onFriendshipAccepted(new FriendshipAcceptedEvent(creator, friend, Instant.now()));

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(FeedTimelinesUpdatedEvent.class,
                event -> assertThat(event.userIds()).containsExactly(friend));
    }

    @Test
    void aNewFriendshipThatGainedNothingTellsNobody() {
        when(fanOut.backfillNewFriendship(creator, friend)).thenReturn(Set.of());

        listener.onFriendshipAccepted(new FriendshipAcceptedEvent(creator, friend, Instant.now()));

        verify(events, never()).publishEvent(any(Object.class));
    }
}
