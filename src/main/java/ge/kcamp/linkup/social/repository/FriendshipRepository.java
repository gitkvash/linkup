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

    /**
     * Accepted-friend counts for many users in one round trip, for the feed's
     * influencer check. Counting them one at a time meant a user with N friends cost
     * N+1 queries per feed page, each hydrating that friend's whole friendship set.
     * <p>
     * Through {@code app_accepted_friend_counts} (V29), a SECURITY DEFINER function,
     * rather than against the table. On a request connection {@code friendships} is
     * filtered to the caller's own rows, so counting there gave every friend a count of
     * one. The function counts every row and returns only the numbers. Users with no
     * accepted friends are absent from the result.
     */
    default List<Object[]> countAcceptedFriendsGrouped(Collection<UUID> userIds) {
        return countAcceptedFriendsGrouped(toArrayLiteral(userIds));
    }

    /**
     * Takes the ids as one Postgres array literal ({@code {a,b}}) rather than a
     * collection. Hibernate expands a collection parameter to {@code (?,?)} once it has
     * two or more elements, so {@code ARRAY[:userIds]} became an array of one row and
     * failed with "cannot cast type record to uuid" - only for a viewer with two or
     * more friends, which no single-friend test notices.
     */
    @Query(value = """
            SELECT user_id, friend_count
            FROM app_accepted_friend_counts(CAST(:userIds AS uuid[]))
            """, nativeQuery = true)
    List<Object[]> countAcceptedFriendsGrouped(@Param("userIds") String userIdsArrayLiteral);

    private static String toArrayLiteral(Collection<UUID> userIds) {
        StringBuilder literal = new StringBuilder("{");
        for (UUID id : userIds) {
            if (literal.length() > 1) {
                literal.append(',');
            }
            literal.append(id);
        }
        return literal.append('}').toString();
    }

    /** The single-user form of {@link #countAcceptedFriendsGrouped}; zero when absent. */
    @Query(value = """
            SELECT COALESCE((
                SELECT friend_count
                FROM app_accepted_friend_counts(CAST(ARRAY[:userId] AS uuid[]))
            ), 0)
            """, nativeQuery = true)
    long countAcceptedFriendsOf(@Param("userId") UUID userId);
}
