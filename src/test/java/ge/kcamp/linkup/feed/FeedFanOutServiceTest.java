package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityQueryService;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.SocialGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who a plan's timeline entry is written to, and taken back from. */
class FeedFanOutServiceTest {

    private static final long INFLUENCER_THRESHOLD = 500;

    private final UUID creator = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID invitee = UUID.randomUUID();
    private final UUID activityId = UUID.randomUUID();
    private final Instant start = Instant.now();

    private RedisFeedTimelineRepository timelines;
    private SocialGraphService socialGraph;
    private GroupService groups;
    private ActivityQueryService activities;
    private FeedFanOutService service;

    @BeforeEach
    void setUp() {
        timelines = mock(RedisFeedTimelineRepository.class);
        socialGraph = mock(SocialGraphService.class);
        groups = mock(GroupService.class);
        activities = mock(ActivityQueryService.class);
        service = new FeedFanOutService(timelines, socialGraph, groups, activities, INFLUENCER_THRESHOLD);
        when(socialGraph.getAcceptedFriendIds(creator)).thenReturn(List.of(friend, invitee));
    }

    @Test
    void aPrivatePlanReachesOnlyItsCreatorAndInvitees() {
        Set<UUID> reached = service.fanOutOnWrite(
                creator, activityId, start, ActivityVisibility.PRIVATE, null, List.of(invitee));

        assertThat(reached).containsExactlyInAnyOrder(creator, invitee);
        verify(timelines).pushAll(argThat(sameAs(creator, invitee)), eq(activityId), anyDouble());
        verify(socialGraph, never()).getAcceptedFriendIds(any());
    }

    @Test
    void aFriendsPlanReachesEveryFriend() {
        Set<UUID> reached = service.fanOutOnWrite(
                creator, activityId, start, ActivityVisibility.FRIENDS, null, List.of(invitee));

        assertThat(reached).containsExactlyInAnyOrder(creator, friend, invitee);
    }

    @Test
    void anInfluencersPlanReachesOnlyTheCreatorAndTheInvitees() {
        when(socialGraph.countAcceptedFriends(creator)).thenReturn(INFLUENCER_THRESHOLD);

        Set<UUID> reached = service.fanOutOnWrite(
                creator, activityId, start, ActivityVisibility.PUBLIC, null, List.of(invitee));

        assertThat(reached).containsExactlyInAnyOrder(creator, invitee);
    }

    @Test
    void aGroupPlanReachesTheGroupInsteadOfTheFriends() {
        UUID groupId = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        when(groups.memberIds(groupId)).thenReturn(List.of(creator, member));

        Set<UUID> reached = service.fanOutOnWrite(
                creator, activityId, start, ActivityVisibility.GROUP, groupId, List.of());

        assertThat(reached).containsExactlyInAnyOrder(creator, member);
    }

    @Test
    void aDeletedPlanIsRemovedFromTheSameAudience() {
        Set<UUID> removed = service.removeEverywhere(
                creator, activityId, ActivityVisibility.PRIVATE, null, List.of(invitee));

        assertThat(removed).containsExactlyInAnyOrder(creator, invitee);
        verify(timelines).removeFromAll(argThat(sameAs(creator, invitee)), eq(activityId));
    }

    @Test
    void pruningDropsOnlyTheEntriesTheUserCanNoLongerSee() {
        UUID stillVisible = UUID.randomUUID();
        UUID lost = UUID.randomUUID();
        when(timelines.readAll(friend)).thenReturn(List.of(stillVisible, lost));
        when(activities.findVisibleIds(List.of(stillVisible, lost), friend)).thenReturn(Set.of(stillVisible));

        assertThat(service.pruneInvisible(friend)).isTrue();
        verify(timelines).remove(friend, List.of(lost));
    }

    @Test
    void pruningAnEmptyTimelineQueriesNothing() {
        when(timelines.readAll(friend)).thenReturn(List.of());

        assertThat(service.pruneInvisible(friend)).isFalse();
        verify(activities, never()).findVisibleIds(any(), any());
    }

    private static ArgumentMatcher<Collection<UUID>> sameAs(UUID... expected) {
        return actual -> Set.copyOf(actual).equals(Set.of(expected));
    }
}
