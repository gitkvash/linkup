package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.SocialGraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FeedFanOutServiceIT extends AbstractIntegrationTest {

    @Autowired
    private FeedFanOutService feedFanOutService;

    @Autowired
    private RedisFeedTimelineRepository timelineRepository;

    @Autowired
    private SocialGraphService socialGraphService;

    @Autowired
    private GroupService groupService;

    @Test
    void fanOutOnWritePushesToBothCreatorAndAcceptedFriendTimelines() {
        UUID creatorId = UUID.randomUUID();
        UUID friendId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        Instant startTime = Instant.now();

        socialGraphService.sendFriendRequest(creatorId, friendId);
        socialGraphService.acceptFriendRequest(friendId, creatorId);

        feedFanOutService.fanOutOnWrite(creatorId, activityId, startTime, null);

        Double justAbove = FeedTimelineScore.of(activityId, startTime) + 1;
        List<UUID> creatorTimeline = timelineRepository.read(creatorId, justAbove, 10);
        List<UUID> friendTimeline = timelineRepository.read(friendId, justAbove, 10);

        assertThat(creatorTimeline).contains(activityId);
        assertThat(friendTimeline).contains(activityId);
    }

    @Test
    void fanOutOnWriteDoesNotPushToNonFriends() {
        UUID creatorId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        Instant startTime = Instant.now();

        feedFanOutService.fanOutOnWrite(creatorId, activityId, startTime, null);

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
        UUID creatorId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        Instant startTime = Instant.now();

        UUID groupId = groupService.createGroup(creatorId, "fan-out probe").getId();
        groupService.addMember(creatorId, groupId, memberId);

        feedFanOutService.fanOutOnWrite(creatorId, activityId, startTime, groupId);

        Double justAbove = FeedTimelineScore.of(activityId, startTime) + 1;
        assertThat(timelineRepository.read(creatorId, justAbove, 10)).contains(activityId);
        assertThat(timelineRepository.read(memberId, justAbove, 10)).contains(activityId);
    }

    /** The read cursor is exclusive, so an item is never served twice. */
    @Test
    void readExcludesTheCursorItself() {
        UUID userId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        Instant startTime = Instant.now();

        feedFanOutService.fanOutOnWrite(userId, activityId, startTime, null);

        double ownScore = FeedTimelineScore.of(activityId, startTime);
        assertThat(timelineRepository.read(userId, ownScore, 10)).doesNotContain(activityId);
        assertThat(timelineRepository.read(userId, null, 10)).contains(activityId);
    }
}
