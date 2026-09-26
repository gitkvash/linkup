package ge.kcamp.linkup.people.dto;

import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.social.CommonGroup;
import ge.kcamp.linkup.social.Relationship;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Someone else's profile, as the caller sees it: {@code GET /users/{id}/profile}.
 *
 * @param relationship        where the caller stands with them. Never BLOCKED - a blocked
 *                            pair gets a 404, the same as an account that doesn't exist.
 * @param friendsSince        null unless they are friends, and for friendships older than
 *                            the column that records it.
 * @param muted               whether the caller has muted their plans.
 * @param togetherCount       plans the two both took part in that happened.
 * @param favouriteCategories what they do most, from the plans of theirs that happened and
 *                            that the caller may see; most first.
 * @param mutualFriends       the caller's friends who are theirs too, by name.
 * @param groupsInCommon      groups both are in, by name.
 */
public record UserProfileDto(
        UUID userId,
        String username,
        String displayName,
        String bio,
        Relationship.Kind relationship,
        Instant friendsSince,
        boolean muted,
        ProfileCountsDto stats,
        long togetherCount,
        List<CategoryCountDto> favouriteCategories,
        List<UserSummary> mutualFriends,
        List<CommonGroup> groupsInCommon
) {
}
