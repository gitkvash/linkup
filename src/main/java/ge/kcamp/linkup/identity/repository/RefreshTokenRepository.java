package ge.kcamp.linkup.identity.repository;

import ge.kcamp.linkup.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Every write here is a single conditional statement rather than read-modify-write, and
 * every one is also scoped to the user the token was signed for, so a jti alone can never
 * touch another account's row.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Marks a live token used and names its successor. Returns 1 if this call did it, 0 if
     * the token is unknown, expired or already revoked.
     * <p>
     * The {@code revokedAt IS NULL} condition is the concurrency control. Two refreshes
     * racing on one token both reach this UPDATE; the second blocks on the row lock, then
     * re-evaluates the condition against the committed row and matches nothing. A
     * read-then-update would let both see an unrevoked row and both rotate it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now, t.replacedBy = :replacedBy
            WHERE t.id = :id AND t.userId = :userId
              AND t.revokedAt IS NULL AND t.expiresAt > :now
            """)
    int rotate(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("replacedBy") UUID replacedBy,
            @Param("now") Instant now);

    /** Sign-out: revokes without a successor. Idempotent - 0 rows is not an error. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now
            WHERE t.id = :id AND t.userId = :userId AND t.revokedAt IS NULL
            """)
    int revoke(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /** A stolen token was replayed: every session of the account ends. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAll(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Pruning, run for one user on every issue so the table never needs a sweeper (the app
     * has no {@code @EnableScheduling}). Expired rows are dead weight - the JWT's own
     * {@code exp} already refuses them. Revoked rows are kept for a while because they are
     * what reuse detection reads, then dropped: an active user refreshes several times a
     * day, and six months of rotated rows per person would otherwise pile up. A replayed
     * token whose row has been pruned is still refused, just without the revoke-all.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM RefreshToken t
            WHERE t.userId = :userId AND (t.expiresAt < :now OR t.revokedAt < :revokedBefore)
            """)
    int deleteStale(
            @Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("revokedBefore") Instant revokedBefore);
}
