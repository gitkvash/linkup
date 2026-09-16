package ge.kcamp.linkup.social.repository;

import ge.kcamp.linkup.social.entity.Friendship;
import ge.kcamp.linkup.social.entity.FriendshipId;
import ge.kcamp.linkup.social.enums.FriendshipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FriendshipRepository extends JpaRepository<Friendship, FriendshipId> {

    Optional<Friendship> findByIdUserAIdAndIdUserBId(UUID userAId, UUID userBId);

    List<Friendship> findByIdUserAIdOrIdUserBId(UUID userAId, UUID userBId);

    /**
     * Projects just the other party's id. The previous approach hydrated every
     * {@link Friendship} row for the user and filtered in Java.
     */
    @Query("""
            SELECT CASE WHEN f.id.userAId = :userId THEN f.id.userBId ELSE f.id.userAId END
            FROM Friendship f
            WHERE f.status = :status
              AND (f.id.userAId = :userId OR f.id.userBId = :userId)
            """)
    List<UUID> findFriendIdsByStatus(@Param("userId") UUID userId, @Param("status") FriendshipStatus status);

    /**
     * Rows for one user in one state. The callers that want only PENDING or only BLOCKED
     * edges used to fetch every friendship the user has - an accepted-friend list that
     * grows without bound - and throw all but a handful away in Java. Both
     * {@code idx_friendships_user_a_status} and {@code idx_friendships_user_b_status}
     * cover this predicate.
     */
    @Query("""
            SELECT f FROM Friendship f
            WHERE f.status = :status
              AND (f.id.userAId = :userId OR f.id.userBId = :userId)
            """)
    List<Friendship> findByStatusForUser(@Param("userId") UUID userId, @Param("status") FriendshipStatus status);

    @Query("""
            SELECT count(f) FROM Friendship f
            WHERE f.status = :status
              AND (f.id.userAId = :userId OR f.id.userBId = :userId)
            """)
    long countByStatusForUser(@Param("userId") UUID userId, @Param("status") FriendshipStatus status);

    /**
     * Accepted-friend counts for many users in one round trip, for the feed's
     * influencer check. Counting them one at a time meant a user with N friends cost
     * N+1 queries per feed page, each hydrating that friend's whole friendship set.
     * <p>
     * Native, because the two-column-key layout can't express "count per user across
     * both sides" in JPQL without a CASE that breaks when both sides are in the set.
     */
    @Query(value = """
            SELECT uid, count(*) AS friend_count FROM (
                SELECT user_a_id AS uid FROM friendships
                 WHERE status = 'ACCEPTED' AND user_a_id IN (:userIds)
                UNION ALL
                SELECT user_b_id AS uid FROM friendships
                 WHERE status = 'ACCEPTED' AND user_b_id IN (:userIds)
            ) both_sides
            GROUP BY uid
            """, nativeQuery = true)
    List<Object[]> countAcceptedFriendsGrouped(@Param("userIds") Collection<UUID> userIds);
}
