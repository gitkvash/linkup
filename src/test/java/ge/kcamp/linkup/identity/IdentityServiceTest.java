package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.dto.AuthResponse;
import ge.kcamp.linkup.identity.entity.RefreshToken;
import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.exception.AuthenticationFailedException;
import ge.kcamp.linkup.identity.exception.RateLimitExceededException;
import ge.kcamp.linkup.identity.exception.SessionExpiredException;
import ge.kcamp.linkup.identity.repository.RefreshTokenRepository;
import ge.kcamp.linkup.identity.repository.UserRepository;
import ge.kcamp.linkup.identity.security.GoogleTokenVerifier;
import ge.kcamp.linkup.identity.security.JwtUtil;
import ge.kcamp.linkup.identity.security.LoginAttemptLimiter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The refresh-token state machine and the login's uniform cost, against mocked
 * repositories. What the database itself guarantees - that the conditional UPDATE lets
 * exactly one of two racing refreshes through - is the repository's contract, and is
 * modelled here by {@code rotate} returning 1 or 0.
 */
class IdentityServiceTest {

    private static final String SECRET = "dGVzdC1vbmx5LWtleS1mb3Itand0LXV0aWwtdGVzdHMtMDEyMzQ1Njc4OQ==";
    private static final long DAY = 86_400_000L;

    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 900_000L, 180 * DAY, new MockEnvironment());
    private final CountingEncoder encoder = new CountingEncoder();

    private IdentityService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = newService(new LoginAttemptLimiter(true, 100, 900, 1000));
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("nino");
        user.setPasswordHash(encoder.encode("correct horse"));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        encoder.matchesCalls.clear();
    }

    private IdentityService newService(LoginAttemptLimiter limiter) {
        return new IdentityService(users, refreshTokens, encoder, jwtUtil, google, limiter);
    }

    // --- refresh -------------------------------------------------------------------

    @Test
    void refreshRotatesTheTokenAndPersistsItsSuccessor() {
        JwtUtil.IssuedRefreshToken presented = jwtUtil.generateRefreshToken(user.getId());
        when(refreshTokens.rotate(eq(presented.jti()), eq(user.getId()), any(), any())).thenReturn(1);

        AuthResponse response = service.refresh(presented.token());

        JwtUtil.RefreshClaims next = jwtUtil.parseRefreshToken(response.refreshToken()).orElseThrow();
        assertThat(next.jti()).isNotEqualTo(presented.jti());
        verify(refreshTokens).rotate(eq(presented.jti()), eq(user.getId()), eq(next.jti()), any());
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(next.jti());
        assertThat(saved.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(saved.getValue().isNew()).isTrue();
        verify(refreshTokens, never()).revokeAll(any(), any());
    }

    /** The response to a refresh was lost and the client tried again with the old token. */
    @Test
    void retryJustAfterRotationGetsAFreshPairWithoutEndingOtherSessions() {
        JwtUtil.IssuedRefreshToken presented = jwtUtil.generateRefreshToken(user.getId());
        when(refreshTokens.rotate(any(), any(), any(), any())).thenReturn(0);
        when(refreshTokens.findById(presented.jti())).thenReturn(Optional.of(
                rotatedRow(presented, Instant.now().minusSeconds(5))));

        AuthResponse response = service.refresh(presented.token());

        assertThat(jwtUtil.parseRefreshToken(response.refreshToken())).isPresent();
        verify(refreshTokens, never()).revokeAll(any(), any());
        verify(refreshTokens).save(any());
    }

    @Test
    void reuseOfARotatedTokenRevokesEverySessionOfTheAccount() {
        JwtUtil.IssuedRefreshToken presented = jwtUtil.generateRefreshToken(user.getId());
        when(refreshTokens.rotate(any(), any(), any(), any())).thenReturn(0);
        when(refreshTokens.findById(presented.jti())).thenReturn(Optional.of(
                rotatedRow(presented, Instant.now().minus(IdentityService.ROTATION_GRACE).minusSeconds(1))));

        assertThatThrownBy(() -> service.refresh(presented.token()))
                .isInstanceOf(SessionExpiredException.class);

        verify(refreshTokens).revokeAll(eq(user.getId()), any());
        verify(refreshTokens, never()).save(any());
    }

    /** No successor, so no live session descends from it: refuse, but don't sign out everywhere. */
    @Test
    void signedOutTokenIsRefusedWithoutRevokingOtherSessions() {
        JwtUtil.IssuedRefreshToken presented = jwtUtil.generateRefreshToken(user.getId());
        RefreshToken row = RefreshToken.issued(
                presented.jti(), user.getId(), presented.issuedAt(), presented.expiresAt());
        row.setRevokedAt(Instant.now().minusSeconds(5));
        when(refreshTokens.rotate(any(), any(), any(), any())).thenReturn(0);
        when(refreshTokens.findById(presented.jti())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.refresh(presented.token()))
                .isInstanceOf(SessionExpiredException.class);

        verify(refreshTokens, never()).revokeAll(any(), any());
    }

    @Test
    void tokenWithNoRowIsRefused() {
        JwtUtil.IssuedRefreshToken presented = jwtUtil.generateRefreshToken(user.getId());
        when(refreshTokens.rotate(any(), any(), any(), any())).thenReturn(0);
        when(refreshTokens.findById(presented.jti())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(presented.token()))
                .isInstanceOf(SessionExpiredException.class);
        verify(refreshTokens, never()).save(any());
    }

    /** A row whose jti matches but which belongs to someone else is not this token's row. */
    @Test
    void rowOfAnotherUserIsNotConsulted() {
        JwtUtil.IssuedRefreshToken presented = jwtUtil.generateRefreshToken(user.getId());
        RefreshToken foreign = RefreshToken.issued(
                presented.jti(), UUID.randomUUID(), presented.issuedAt(), presented.expiresAt());
        foreign.setRevokedAt(Instant.now().minusSeconds(1));
        foreign.setReplacedBy(UUID.randomUUID());
        when(refreshTokens.rotate(any(), any(), any(), any())).thenReturn(0);
        when(refreshTokens.findById(presented.jti())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.refresh(presented.token()))
                .isInstanceOf(SessionExpiredException.class);
        verify(refreshTokens, never()).save(any());
    }

    /** Issued before this change: signed and unexpired, but with no jti there is no row. */
    @Test
    void preRotationRefreshTokenForcesOneSignIn() {
        String legacy = Jwts.builder()
                .setSubject(user.getId().toString())
                .claim("typ", "refresh")
                .setExpiration(new Date(System.currentTimeMillis() + DAY))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThatThrownBy(() -> service.refresh(legacy)).isInstanceOf(SessionExpiredException.class);
        verifyNoInteractions(refreshTokens);
    }

    // --- logout --------------------------------------------------------------------

    @Test
    void logoutRevokesThePresentedToken() {
        JwtUtil.IssuedRefreshToken presented = jwtUtil.generateRefreshToken(user.getId());

        service.logout(presented.token());

        verify(refreshTokens).revoke(eq(presented.jti()), eq(user.getId()), any());
    }

    @Test
    void logoutWithAnythingElseQuietlyDoesNothing() {
        service.logout(null);
        service.logout("");
        service.logout("not a token");
        service.logout(jwtUtil.generateToken(user.getId(), "nino"));

        verifyNoInteractions(refreshTokens);
    }

    // --- issuing -------------------------------------------------------------------

    @Test
    void everySignInPersistsItsRefreshTokenAndPrunesDeadRows() {
        when(users.findByUsernameIgnoringCase("nino")).thenReturn(Optional.of(user));

        AuthResponse response = service.login("nino", "correct horse");

        UUID jti = jwtUtil.parseRefreshToken(response.refreshToken()).orElseThrow().jti();
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(jti);
        verify(refreshTokens).deleteStale(eq(user.getId()), any(), any());
    }

    // --- login ---------------------------------------------------------------------

    @Test
    void unknownUsernameStillCostsOneHashComparison() {
        when(users.findByUsernameIgnoringCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost", "whatever"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(encoder.matchesCalls).hasSize(1);
        assertThat(encoder.matchesCalls.getFirst()).isNotNull();
    }

    @Test
    void googleOnlyAccountStillCostsOneHashComparison() {
        User googleOnly = new User();
        googleOnly.setId(UUID.randomUUID());
        googleOnly.setUsername("g");
        when(users.findByUsernameIgnoringCase("g")).thenReturn(Optional.of(googleOnly));

        assertThatThrownBy(() -> service.login("g", "whatever"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(encoder.matchesCalls).hasSize(1);
        assertThat(encoder.matchesCalls.getFirst()).isNotNull();
    }

    @Test
    void wrongPasswordAndUnknownUserFailTheSameWay() {
        when(users.findByUsernameIgnoringCase("nino")).thenReturn(Optional.of(user));
        when(users.findByUsernameIgnoringCase("ghost")).thenReturn(Optional.empty());

        Throwable wrongPassword = catchThrowable(
                () -> service.login("nino", "wrong"));
        Throwable unknownUser = catchThrowable(
                () -> service.login("ghost", "wrong"));

        assertThat(wrongPassword).isInstanceOf(AuthenticationFailedException.class);
        assertThat(unknownUser).isInstanceOf(AuthenticationFailedException.class)
                .hasMessage(wrongPassword.getMessage());
    }

    @Test
    void loginAttemptsAreLimitedPerUsernameRegardlessOfCase() {
        IdentityService limited = newService(new LoginAttemptLimiter(true, 2, 900, 1000));
        when(users.findByUsernameIgnoringCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> limited.login("Nino", "x")).isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> limited.login("nino", "x")).isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> limited.login("NINO", "x"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds()).isPositive());
        // A different account is unaffected.
        assertThatThrownBy(() -> limited.login("dato", "x")).isInstanceOf(AuthenticationFailedException.class);
    }

    // --- Google --------------------------------------------------------------------

    @Test
    void googleAccountGetsAUsernameThatSaysNothingAboutTheEmail() {
        when(google.verify("id-token")).thenReturn(
                new GoogleTokenVerifier.Result("google-sub", "giorgi.k@example.com"));
        when(users.findByGoogleId("google-sub")).thenReturn(Optional.empty());
        when(users.existsByUsernameIgnoringCase(anyString())).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User created = invocation.getArgument(0);
            created.setId(UUID.randomUUID());
            return created;
        });

        AuthResponse response = service.loginWithGoogle("id-token");

        assertThat(response.newAccount()).isTrue();
        assertThat(response.username()).matches("user[a-z0-9]{8}").doesNotContainIgnoringCase("giorgi");
    }

    // -------------------------------------------------------------------------------

    private RefreshToken rotatedRow(JwtUtil.IssuedRefreshToken token, Instant revokedAt) {
        RefreshToken row = RefreshToken.issued(token.jti(), user.getId(), token.issuedAt(), token.expiresAt());
        row.setRevokedAt(revokedAt);
        row.setReplacedBy(UUID.randomUUID());
        return row;
    }

    /** BCrypt at its lowest cost, recording which hash each comparison was made against. */
    private static final class CountingEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder(4);
        private final List<String> matchesCalls = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls.add(encodedPassword);
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
