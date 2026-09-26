package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.DatabaseRole;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.SocialGraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Friend requests and groups are written as the user making them, as a request would;
 * fan-out and backfill run as {@link DatabaseRole#SYSTEM}, as they do on the listener
 * thread in production ({@code FeedFanOutEventListener}).
 */
@SpringBootTest
class FeedFanOutServiceIT extends AbstractIntegrationTest {

    @Autowired
    private FeedFanOutService feedFanOutService;

    @Autowired
    private RedisFeedTimelineRepository timelineRepository;

    @Autowired
    private SocialGraphService socialGraphService;

    @Autowired
    private GroupService groupService;

    @Autowired
    private ActivityRepository activityRepository;

    @Test
    void fanOutOnWritePushesToBothCreatorAndAcceptedFriendTimelines() {
        UUID creatorId = newUser();
        UUID friendId = newUser();
        UUID activityId = UUID.randomUUID();
        Instant startTime = Instant.now();

        befriend(creatorId, friendId);

        DatabaseRole.runAsSystem(() ->
                feedFanOutService.fanOutOnWrite(creatorId, activityId, startTime, null));

        Double justAbove = FeedTimelineScore.of(activityId, startTime) + 1;
        List<UUID> creatorTimeline = timelineRepository.read(creatorId, justAbove, 10);
        List<UUID> friendTimeline = timelineRepository.read(friendId, justAbove, 10);

        assertThat(creatorTimeline).contains(activityId);
        assertThat(friendTimeline).contains(activityId);
    }

    @Test
    void fanOutOnWriteDoesNotPushToNonFriends() {
        UUID creatorId = newUser();
        UUID strangerId = newUser();
        UUID activityId = UUID.randomUUID();
        Instant startTime = Instant.now();

        DatabaseRole.runAsSystem(() ->
                feedFanOutService.fanOutOnWrite(creatorId, activityId, startTime, null));

        List<UUID> strangerTimeline =
                timelineRepository.read(strangerId, FeedTimelineScore.of(activityId, startTime) + 1, 10);
        assertThat(strangerTimeline).doesNotContain(activityId);
    }

    /**
     * The whole point of GROUP visibility: the member is not a friend, so the friend
     * branch would never have reached them and the plan would have existed only on the
     * map and behind a direct link.
     */
    @Test
    void fanOutOnWriteReachesGroupMembersWhoAreNotFriends() {
        UUID memberId = newUser();
        UUID creatorId = actAs(newUser());
        UUID activityId = UUID.randomUUID();
        Instant startTime = Instant.now();

        UUID groupId = groupService.createGroup(creatorId, "fan-out probe").getId();
        groupService.addMember(creatorId, groupId, memberId);

        DatabaseRole.runAsSystem(() ->
                feedFanOutService.fanOutOnWrite(creatorId, activityId, startTime, groupId));

        Double justAbove = FeedTimelineScore.of(activityId, startTime) + 1;
        assertThat(timelineRepository.read(creatorId, justAbove, 10)).contains(activityId);
        assertThat(timelineRepository.read(memberId, justAbove, 10)).contains(activityId);
    }

    /**
     * Fan-out runs once, at creation, so a plan made before two people became friends
     * never reached the new friend's feed - only the map. Accepting the request brings
     * the other side's plans that are still on, and leaves the ended ones out.
     */
    @Test
    void aNewFriendshipBringsTheOtherSidesPlansThatAreStillOn() {
        UUID friendId = newUser();
        UUID creatorId = actAs(newUser());
        UUID upcoming = createPublicPlan(creatorId, ZonedDateTime.now().plusHours(2));
        UUID ended = createPublicPlan(creatorId, ZonedDateTime.now().minusHours(5));

        befriend(creatorId, friendId);
        DatabaseRole.runAsSystem(() -> feedFanOutService.backfillNewFriendship(creatorId, friendId));

        List<UUID> friendTimeline = timelineRepository.read(friendId, null, 10);
        assertThat(friendTimeline).contains(upcoming);
        assertThat(friendTimeline).doesNotContain(ended);
    }

    private void befriend(UUID requesterId, UUID accepterId) {
        actAs(requesterId);
        socialGraphService.sendFriendRequest(requesterId, accepterId);
        actAs(accepterId);
        socialGraphService.acceptFriendRequest(accepterId, requesterId);
    }

    private UUID createPublicPlan(UUID creatorId, ZonedDateTime startTime) {
        return activityRepository.save(Activity.builder()
                .creatorId(creatorId)
                .activityType(ActivityType.SPECIFIC_EVENT)
                .title("backfill probe")
                .visibility(ActivityVisibility.PUBLIC)
                .category(ActivityCategory.GENERAL)
                .startTime(startTime)
                .build()).getId();
    }

    /** The read cursor is exclusive, so an item is never served twice. */
    @Test
    void readExcludesTheCursorItself() {
        UUID userId = newUser();
        UUID activityId = UUID.randomUUID();
        Instant startTime = Instant.now();

        DatabaseRole.runAsSystem(() ->
                feedFanOutService.fanOutOnWrite(userId, activityId, startTime, null));

        double ownScore = FeedTimelineScore.of(activityId, startTime);
        assertThat(timelineRepository.read(userId, ownScore, 10)).doesNotContain(activityId);
        assertThat(timelineRepository.read(userId, null, 10)).contains(activityId);
    }
}
