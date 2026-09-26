package ge.kcamp.linkup.people;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityHistoryService;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.people.dto.CategoryCountDto;
import ge.kcamp.linkup.people.dto.ProfileCountsDto;
import ge.kcamp.linkup.people.dto.UserProfileDto;
import ge.kcamp.linkup.people.repository.PeopleQueryRepository;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.Relationship;
import ge.kcamp.linkup.social.SocialGraphService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Someone else's profile, and the plan lists under its tabs.
 * <p>
 * Both answer empty - a 404 - for an account that doesn't exist and for a pair where
 * either has blocked the other, and the two are indistinguishable on purpose: a block is
 * not something the blocked person gets told about (see {@code SocialGraphService}).
 */
@Service
@Transactional(readOnly = true)
public class PersonProfileService {

    /** Categories shown as "their go-to". */
    private static final int FAVOURITE_CATEGORIES = 6;

    private final UserDirectoryService userDirectoryService;
    private final SocialGraphService socialGraphService;
    private final GroupService groupService;
    private final ActivityHistoryService activityHistoryService;
    private final PeopleQueryRepository peopleQueryRepository;

    public PersonProfileService(
            UserDirectoryService userDirectoryService,
            SocialGraphService socialGraphService,
            GroupService groupService,
            ActivityHistoryService activityHistoryService,
            PeopleQueryRepository peopleQueryRepository) {
        this.userDirectoryService = userDirectoryService;
        this.socialGraphService = socialGraphService;
        this.groupService = groupService;
        this.activityHistoryService = activityHistoryService;
        this.peopleQueryRepository = peopleQueryRepository;
    }

    public enum ActivityScope {
        /** Their plans, hosting or going, that haven't finished. */
        UPCOMING,
        /** Plans the two did together. */
        TOGETHER
    }

    public Optional<UserProfileDto> profile(UUID viewerId, UUID userId) {
        Optional<UserSummary> found = userDirectoryService.findById(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Relationship relationship = socialGraphService.relationship(viewerId, userId);
        if (relationship.kind() == Relationship.Kind.BLOCKED) {
            return Optional.empty();
        }
        UserSummary user = found.get();

        PeopleQueryRepository.Counts counts = peopleQueryRepository.countsFor(userId);
        long together = activityHistoryService.happenedTogether(viewerId, userId).size();

        List<UserSummary> mutual = relationship.kind() == Relationship.Kind.SELF
                ? List.of()
                : userDirectoryService.findByIds(peopleQueryRepository.mutualFriendIds(userId)).values().stream()
                        .sorted(Comparator.comparing(
                                summary -> summary.name() == null ? "" : summary.name().toLowerCase()))
                        .toList();

        return Optional.of(new UserProfileDto(
                user.userId(),
                user.username(),
                user.displayName(),
                user.bio(),
                relationship.kind(),
                relationship.friendsSince(),
                relationship.muted(),
                new ProfileCountsDto(counts.friends(), counts.hosted(), counts.groups()),
                together,
                favouriteCategories(userId, viewerId),
                mutual,
                groupService.groupsInCommon(viewerId, userId)));
    }

    /** Empty for the same people {@link #profile} is empty for. */
    public Optional<List<ActivityFeedItem>> activities(UUID viewerId, UUID userId, ActivityScope scope) {
        if (userDirectoryService.findById(userId).isEmpty()
                || socialGraphService.isBlockedEitherWay(viewerId, userId)) {
            return Optional.empty();
        }
        return Optional.of(switch (scope) {
            case UPCOMING -> activityHistoryService.upcomingFor(userId, viewerId);
            case TOGETHER -> activityHistoryService.happenedTogether(viewerId, userId);
        });
    }

    private List<CategoryCountDto> favouriteCategories(UUID userId, UUID viewerId) {
        Map<ActivityCategory, Long> counts = new EnumMap<>(ActivityCategory.class);
        for (ActivityFeedItem item : activityHistoryService.happenedBetween(
                userId, viewerId, Instant.EPOCH, Instant.now())) {
            counts.merge(item.category(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<ActivityCategory, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(FAVOURITE_CATEGORIES)
                .map(entry -> new CategoryCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }
}
