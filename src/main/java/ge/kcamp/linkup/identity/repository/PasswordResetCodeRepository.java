package ge.kcamp.linkup.identity.repository;

import ge.kcamp.linkup.identity.entity.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Every write is one statement rather than read-modify-write, for the reason
 * {@link RefreshTokenRepository}'s are: two requests racing on one code must not both get
 * the last attempt.
 */
public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, UUID> {

    /**
     * Stores a new code for the account, replacing any earlier one and resetting its
     * attempt count. An upsert on the primary key, so two simultaneous requests leave one
     * row rather than one and a unique-violation.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO password_reset_codes (user_id, code_hash, created_at, expires_at, failed_attempts)
            VALUES (:userId, :codeHash, :now, :expiresAt, 0)
            ON CONFLICT (user_id) DO UPDATE
               SET code_hash = EXCLUDED.code_hash,
                   created_at = EXCLUDED.created_at,
                   expires_at = EXCLUDED.expires_at,
                   failed_attempts = 0
            """, nativeQuery = true)
    int upsert(
            @Param("userId") UUID userId,
            @Param("codeHash") String codeHash,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    /**
     * Spends one guess. Returns 1 if the account has a live code with a guess left and this
     * call took it, 0 if there is no code, it expired, or the guesses are used up. A correct
     * guess needs no refund: the code is deleted when it is spent.
     * <p>
     * Taken <em>before</em> the code is compared, and it is the concurrency control: two
     * guesses racing for the last attempt both reach this UPDATE, the second blocks on the
     * row lock, re-evaluates {@code failedAttempts < :max} against the committed row and
     * matches nothing. Counting after the comparison would let any number of parallel
     * requests each try a different code.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PasswordResetCode c SET c.failedAttempts = c.failedAttempts + 1
            WHERE c.userId = :userId AND c.expiresAt > :now AND c.failedAttempts < :max
            """)
    int takeAttempt(@Param("userId") UUID userId, @Param("now") Instant now, @Param("max") int max);
}
