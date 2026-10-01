package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityQueryService;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.feed.dto.FeedItemDto;
import ge.kcamp.linkup.feed.dto.FeedPageDto;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.social.SocialGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What a feed card carries about who is going, and that a page costs one lookup for it. */
class FeedQueryServiceTest {

    private final UUID viewer = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();

    private RedisFeedTimelineRepository timeline;
    private ActivityQueryService activities;
    private UserDirectoryService users;
    private FeedQueryService service;

    @BeforeEach
    void setUp() {
        timeline = mock(RedisFeedTimelineRepository.class);
        activities = mock(ActivityQueryService.class);
        users = mock(UserDirectoryService.class);
        FeedFanOutService fanOut = mock(FeedFanOutService.class);
        SocialGraphService social = mock(SocialGraphService.class);
        when(social.getAcceptedFriends(viewer))
                .thenReturn(new SocialGraphService.AcceptedFriends(List.of(), Set.of()));
        when(fanOut.influencersAmong(anyCollection())).thenReturn(Set.of());
        service = new FeedQueryService(timeline, activities, fanOut, users, social);
    }

    @Test
    void aPageLooksUpEveryCardsPreviewAndEveryNameOnce() {
        ActivityFeedItem first = item(UUID.randomUUID(), 5, ZonedDateTime.now().plusDays(1));
        ActivityFeedItem second = item(UUID.randomUUID(), 0, ZonedDateTime.now().plusDays(2));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        when(activities.findByCreatorIn(anyList(), any(), any(), eq(viewer)))
                .thenReturn(List.of(first, second));
        when(activities.findJoinedUserIds(anyCollection(), anyInt()))
                .thenReturn(Map.of(first.activityId(), List.of(creator, alice, bob)));
        when(users.findByIds(anyCollection()))
                .thenReturn(Map.of(creator, person(creator, "cara", "Cara"), alice, person(alice, "alice", null),
                        bob, person(bob, "bob", null)));

        FeedPageDto page = service.getFeed(viewer, null, 20);

        assertThat(page.items()).hasSize(2);
        FeedItemDto withPeople = byId(page, first.activityId());
        assertThat(withPeople.participantCount()).isEqualTo(5);
        assertThat(withPeople.participantPreview()).containsExactly(
                new FeedItemDto.Participant(creator, "cara"),
                new FeedItemDto.Participant(alice, "alice"),
                new FeedItemDto.Participant(bob, "bob"));
        FeedItemDto empty = byId(page, second.activityId());
        assertThat(empty.participantCount()).isZero();
        assertThat(empty.participantPreview()).isEmpty();

        ArgumentCaptor<Collection<UUID>> previewIds = ArgumentCaptor.captor();
        verify(activities, times(1)).findJoinedUserIds(previewIds.capture(), eq(FeedQueryService.PREVIEW_SIZE));
        assertThat(previewIds.getValue()).containsExactlyInAnyOrder(first.activityId(), second.activityId());
        // creators and previewed people share one directory read
        ArgumentCaptor<Collection<UUID>> nameIds = ArgumentCaptor.captor();
        verify(users, times(1)).findByIds(nameIds.capture());
        assertThat(nameIds.getValue()).containsExactlyInAnyOrder(creator, alice, bob);
    }

    @Test
    void anEmptyPageAsksForNoPreviews() {
        when(timeline.readScored(eq(viewer), any(), anyInt())).thenReturn(List.of());
        when(activities.findByCreatorIn(anyList(), any(), any(), eq(viewer))).thenReturn(List.of());
        when(users.findByIds(anyCollection())).thenReturn(Map.of());

        assertThat(service.getFeed(viewer, null, 20).items()).isEmpty();

        verify(activities, times(0)).findJoinedUserIds(anyCollection(), anyInt());
    }

    @Test
    void aPreviewNeverExceedsThreeAndSkipsAccountsThatNoLongerResolve() {
        UUID a = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        ActivityFeedItem item = item(UUID.randomUUID(), 5, ZonedDateTime.now());

        FeedItemDto dto = FeedQueryService.toDto(
                item,
                Map.of(creator, person(creator, "cara", "Cara"), a, person(a, "a", null), b, person(b, "b", null),
                        c, person(c, "c", null), d, person(d, "d", null)),
                List.of(a, gone, b, c, d));

        assertThat(dto.creatorUsername()).isEqualTo("Cara");
        assertThat(dto.participantPreview()).extracting(FeedItemDto.Participant::id)
                .containsExactly(a, b, c);
    }

    @Test
    void theJsonKeysAreIdAndUsername() {
        UUID a = UUID.randomUUID();
        FeedItemDto dto = FeedQueryService.toDto(
                item(UUID.randomUUID(), 7, ZonedDateTime.now()),
                Map.of(creator, person(creator, "cara", "Cara"), a, person(a, "alice", "Alice B.")),
                List.of(a));

        JsonNode json = JsonMapper.builder().build().valueToTree(dto);

        assertThat(json.get("participantCount").isNumber()).isTrue();
        assertThat(json.get("participantCount").asLong()).isEqualTo(7);
        JsonNode preview = json.get("participantPreview");
        assertThat(preview.isArray()).isTrue();
        assertThat(preview.get(0).size()).isEqualTo(2);
        assertThat(preview.get(0).has("id")).isTrue();
        assertThat(preview.get(0).has("username")).isTrue();
        assertThat(preview.get(0).get("id").asString()).isEqualTo(a.toString());
        assertThat(preview.get(0).get("username").asString()).isEqualTo("alice");
    }

    private static UserSummary person(UUID id, String username, String displayName) {
        return new UserSummary(id, username, displayName, null);
    }

    private static FeedItemDto byId(FeedPageDto page, UUID id) {
        return page.items().stream().filter(i -> i.activityId().equals(id)).findFirst().orElseThrow();
    }

    private ActivityFeedItem item(UUID id, long participantCount, ZonedDateTime start) {
        return new ActivityFeedItem(id, creator, "cara", "Cara", "Dinner", ActivityType.SPECIFIC_EVENT,
                ActivityVisibility.PUBLIC, start, null, true, "Somewhere", null, null, participantCount,
                null, null, null, ActivityCategory.FOOD_AND_DRINK, null, null, null, ActivityStatus.UPCOMING);
    }
}
