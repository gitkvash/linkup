package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.DatabaseRole;
import ge.kcamp.linkup.identity.dto.AuthResponse;
import ge.kcamp.linkup.identity.exception.SessionExpiredException;
import ge.kcamp.linkup.identity.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refresh-token rotation against the real schema and the real roles. The unit test
 * covers the decisions; this covers what only Postgres can: that V28's grants let the
 * restricted app role use the table on the unauthenticated /auth paths (no
 * {@code app.current_user_id} set), that the conditional UPDATE and pruning queries are
 * valid, and that the revoke-all survives the exception that follows it.
 * <p>
 * Not {@code @Transactional}: each service call has to commit on its own, as it does in
 * production, or the reuse path's {@code noRollbackFor} would go untested.
 */
@SpringBootTest
class RefreshTokenRotationIT extends AbstractIntegrationTest {

    @Autowired
    private IdentityService identityService;

    @Autowired
    private UserDirectoryService userDirectoryService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthResponse register() {
        return identityService.register("rot_" + UUID.randomUUID().toString().substring(0, 8), "password123");
    }

    @Test
    void refreshRotatesAndTheOldTokenIsOnlyGoodForAGraceRetry() {
        AuthResponse signedIn = register();

        AuthResponse first = identityService.refresh(signedIn.refreshToken());
        // A lost-response retry, moments later: honoured.
        AuthResponse retry = identityService.refresh(signedIn.refreshToken());

        assertThat(first.refreshToken()).isNotEqualTo(signedIn.refreshToken());
        assertThat(retry.refreshToken()).isNotEqualTo(first.refreshToken());
        UUID oldJti = jwtUtil.parseRefreshToken(signedIn.refreshToken()).orElseThrow().jti();
        UUID firstJti = jwtUtil.parseRefreshToken(first.refreshToken()).orElseThrow().jti();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT replaced_by FROM refresh_tokens WHERE id = ?", UUID.class, oldJti))
                .isEqualTo(firstJti);
    }

    @Test
    void replayAfterTheGraceWindowEndsEverySession() {
        AuthResponse signedIn = register();
        AuthResponse otherDevice = identityService.login(signedIn.username(), "password123");
        AuthResponse rotated = identityService.refresh(signedIn.refreshToken());
        UUID oldJti = jwtUtil.parseRefreshToken(signedIn.refreshToken()).orElseThrow().jti();
        // Back-date the rotation past the grace window, as the owner - the app role
        // is not meant to be able to rewrite history, and this is the test reaching in.
        DatabaseRole.runAsSystem(() -> jdbcTemplate.update(
                "UPDATE refresh_tokens SET revoked_at = now() - interval '5 minutes' WHERE id = ?", oldJti));

        assertThatThrownBy(() -> identityService.refresh(signedIn.refreshToken()))
                .isInstanceOf(SessionExpiredException.class);

        // Committed despite the exception, so both live sessions are now dead.
        assertThatThrownBy(() -> identityService.refresh(rotated.refreshToken()))
                .isInstanceOf(SessionExpiredException.class);
        assertThatThrownBy(() -> identityService.refresh(otherDevice.refreshToken()))
                .isInstanceOf(SessionExpiredException.class);
    }

    @Test
    void logoutRevokesTheTokenAndIsIdempotent() {
        AuthResponse signedIn = register();

        identityService.logout(signedIn.refreshToken());
        identityService.logout(signedIn.refreshToken());
        identityService.logout("garbage");

        assertThatThrownBy(() -> identityService.refresh(signedIn.refreshToken()))
                .isInstanceOf(SessionExpiredException.class);
    }

    @Test
    void userSearchTreatsLikeWildcardsLiterally() {
        AuthResponse searcher = register();

        assertThat(userDirectoryService.search("%%", searcher.userId())).isEmpty();
        // Registered usernames here all contain "_", so an unescaped "t_" would match
        // "rot_..." via the wildcard and an escaped one must still match it literally.
        assertThat(userDirectoryService.search("t_", UUID.randomUUID()))
                .allSatisfy(summary -> assertThat(summary.username()).containsIgnoringCase("t_"))
                .isNotEmpty();
    }
}
