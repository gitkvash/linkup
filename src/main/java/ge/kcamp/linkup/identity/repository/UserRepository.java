package ge.kcamp.linkup.identity.repository;

import ge.kcamp.linkup.identity.entity.User;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Login and registration both match without regard to case, so "Alice" logs in as
     * "alice" and cannot register alongside her (V20 enforces the same rule in the
     * database).
     * <p>
     * Written as {@code LOWER(username) = LOWER(:username)} rather than a derived
     * {@code IgnoreCase} query on purpose: Spring Data generates {@code upper(...)} for
     * those, which cannot use {@code ux_users_username_lower} and would turn every login
     * into a sequential scan.
     */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    Optional<User> findByUsernameIgnoringCase(@Param("username") String username);

    @Query("SELECT count(u) > 0 FROM User u WHERE LOWER(u.username) = LOWER(:username)")
    boolean existsByUsernameIgnoringCase(@Param("username") String username);

    Optional<User> findByGoogleId(String googleId);

    /**
     * Case-insensitive prefix-or-substring match, excluding the caller (there's no
     * point offering to befriend yourself).
     * <p>
     * Ordered so prefix matches come first: typing "ni" should surface "nino" above
     * "antonino". Uses LOWER(...) LIKE rather than a derived
     * {@code ContainingIgnoreCase} query so the ordering clause can share the same
     * expression.
     * <p>
     * {@code query} must already be LIKE-escaped with {@code !} (see
     * {@code UserDirectoryService}). Passed through raw, a search for {@code __} or
     * {@code %%} matched every account, which is a directory dump 50 at a time. {@code !}
     * rather than PostgreSQL's default backslash because it means the same thing in HQL
     * and SQL string literals, and no valid username can contain it.
     */
    @Query("""
            SELECT u FROM User u
            WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!'
              AND u.id <> :excludeUserId
            ORDER BY
              CASE WHEN LOWER(u.username) LIKE LOWER(CONCAT(:query, '%')) ESCAPE '!' THEN 0 ELSE 1 END,
              LENGTH(u.username),
              u.username
            """)
    List<User> searchByUsername(
            @Param("query") String query,
            @Param("excludeUserId") UUID excludeUserId,
            Limit limit);
}
