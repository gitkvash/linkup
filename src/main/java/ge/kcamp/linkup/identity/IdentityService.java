package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.dto.AuthResponse;
import ge.kcamp.linkup.identity.entity.RefreshToken;
import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.exception.AuthenticationFailedException;
import ge.kcamp.linkup.identity.exception.DuplicateUsernameException;
import ge.kcamp.linkup.identity.exception.SessionExpiredException;
import ge.kcamp.linkup.identity.repository.RefreshTokenRepository;
import ge.kcamp.linkup.identity.repository.UserRepository;
import ge.kcamp.linkup.identity.security.GoogleTokenVerifier;
import ge.kcamp.linkup.identity.security.JwtUtil;
import ge.kcamp.linkup.identity.security.LoginAttemptLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    /**
     * How long after a rotation the old refresh token is still honoured. The client sends
     * a refresh, the server rotates, the response is lost to a dropped connection, and the
     * client retries with the token it still holds. Without a grace window that retry
     * looks exactly like a stolen token being replayed, and would sign the person out of
     * every device.
     */
    static final Duration ROTATION_GRACE = Duration.ofSeconds(30);

    /**
     * How long a used or revoked refresh token row is kept for reuse detection before
     * {@link RefreshTokenRepository#deleteStale} prunes it. See there.
     */
    static final Duration REVOKED_RETENTION = Duration.ofDays(14);

    /** BCrypt reads at most this many bytes of a password and refuses to hash more. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private static final String GENERATED_USERNAME_PREFIX = "user";
    private static final String GENERATED_USERNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int GENERATED_USERNAME_SUFFIX_LENGTH = 8;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final SecureRandom random = new SecureRandom();

    /**
     * Compared against when there is no hash to compare against - an unknown username, or
     * a Google-only account with no password - so those logins cost one BCrypt check like
     * every other. Returning early made "no such user" answer in a millisecond and a wrong
     * password in ~100: the response time alone enumerated accounts.
     * <p>
     * Produced by this encoder at startup rather than pasted in, so its algorithm and cost
     * are the real ones by construction; a hash with a different cost factor would take a
     * different time and give the game away again.
     */
    private final String dummyPasswordHash;

    public IdentityService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            GoogleTokenVerifier googleTokenVerifier,
            LoginAttemptLimiter loginAttemptLimiter) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.googleTokenVerifier = googleTokenVerifier;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse register(String username, String rawPassword) {
        // Checked here, with our own message: BCrypt refuses longer input with an
        // IllegalArgumentException whose library text the error handler won't echo.
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("That password is too long. Please use a shorter one.");
        }

        // Case-insensitively, so "Alice" cannot be registered alongside "alice" - the
        // spelling the user typed is still what gets stored and shown.
        if (userRepository.existsByUsernameIgnoringCase(username)) {
            throw new DuplicateUsernameException();
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));

        User saved;
        try {
            // saveAndFlush, not save: the unique-constraint violation has to surface
            // here rather than at commit, or two simultaneous registrations of the
            // same username escape this method and become a generic 500.
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUsernameException();
        }

        return issueToken(saved, true);
    }

    /**
     * One generic failure for every way this can go wrong, and one BCrypt comparison on
     * every path to it - see {@link #dummyPasswordHash}. Not read-only any more: signing in
     * writes the new refresh token's row.
     */
    @Transactional
    public AuthResponse login(String username, String rawPassword) {
        loginAttemptLimiter.check(username);

        Optional<User> user = userRepository.findByUsernameIgnoringCase(username);
        String storedHash = user.map(User::getPasswordHash).orElse(null);
        boolean matches = passwordEncoder.matches(
                rawPassword, storedHash != null ? storedHash : dummyPasswordHash);

        if (storedHash == null || !matches) {
            throw new AuthenticationFailedException();
        }
        return issueToken(user.get(), false);
    }

    @Transactional
    public AuthResponse loginWithGoogle(String idToken) {
        GoogleTokenVerifier.Result verified = googleTokenVerifier.verify(idToken);
        Optional<User> existing = userRepository.findByGoogleId(verified.subject());
        User user = existing.orElseGet(() -> createGoogleUser(verified));
        // Only the first time. The generated username is a placeholder, and the client
        // turns this flag into one chance to replace it - on every sign-in it would be
        // a form standing between the user and the app.
        return issueToken(user, existing.isEmpty());
    }

    /**
     * Trades a refresh token for a new access token and a new refresh token, and retires
     * the one presented. The account is re-read rather than trusted from the token, so a
     * deleted account can't keep minting sessions, and the new access token carries the
     * current username.
     * <p>
     * Each refresh token works once. Presenting a used one is either a retry of a refresh
     * whose response was lost - honoured within {@link #ROTATION_GRACE} - or a copy of the
     * token being replayed by someone else, and there is no telling which of the two
     * holders is the owner. Every session of the account is then revoked, so both have to
     * sign in again and only the owner can.
     * <p>
     * {@code noRollbackFor}: that revoke-all is followed by a {@link SessionExpiredException},
     * and rolling back on it would quietly undo the revocation.
     */
    @Transactional(noRollbackFor = SessionExpiredException.class)
    public AuthResponse refresh(String refreshToken) {
        JwtUtil.RefreshClaims claims = jwtUtil.parseRefreshToken(refreshToken)
                .orElseThrow(SessionExpiredException::new);
        User user = userRepository.findById(claims.userId())
                .orElseThrow(SessionExpiredException::new);

        // Minted first, because the rotation records the successor's id.
        JwtUtil.IssuedRefreshToken next = jwtUtil.generateRefreshToken(user.getId());
        Instant now = Instant.now();
        int rotated = refreshTokenRepository.rotate(claims.jti(), claims.userId(), next.jti(), now);
        if (rotated == 0) {
            requireRetryOfRecentRotation(claims, now);
        }
        return persistSession(user, next, false);
    }

    /**
     * Revokes the refresh token, if it is one this server issued and still live. Anything
     * else - garbage, an expired token, one already revoked - is quietly accepted: the
     * caller is leaving either way, and a sign-out that can fail is one the client has to
     * be written to retry.
     * <p>
     * The access token is not revoked. It is stateless and expires within minutes.
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        jwtUtil.parseRefreshToken(refreshToken).ifPresent(claims ->
                refreshTokenRepository.revoke(claims.jti(), claims.userId(), Instant.now()));
    }

    /**
     * The token could not be rotated. Returns only if this is a retry of a rotation that
     * happened moments ago; otherwise throws, revoking every session first if the token
     * is a used one being replayed.
     */
    private void requireRetryOfRecentRotation(JwtUtil.RefreshClaims claims, Instant now) {
        RefreshToken row = refreshTokenRepository.findById(claims.jti())
                .filter(found -> found.getUserId().equals(claims.userId()))
                // Never issued, or pruned. Nothing to protect and nothing to reuse.
                .orElseThrow(SessionExpiredException::new);

        if (row.getRevokedAt() == null) {
            // Live but not rotatable: it expired.
            throw new SessionExpiredException();
        }
        if (row.getReplacedBy() == null) {
            // Signed out, or already caught up in a revoke-all. No successor exists, so
            // there is no live session descended from it to end.
            throw new SessionExpiredException();
        }
        if (row.getRevokedAt().isAfter(now.minus(ROTATION_GRACE))) {
            // The lost-response retry. The successor minted by the first attempt is left
            // alone: if this really is a second concurrent refresh from a client that did
            // receive it, revoking it would turn that client's next refresh into
            // "reuse" and sign the person out everywhere.
            return;
        }

        int revoked = refreshTokenRepository.revokeAll(claims.userId(), now);
        log.warn("Refresh token {} for user {} was reused {} after rotation; revoked {} session(s)",
                claims.jti(), claims.userId(), Duration.between(row.getRevokedAt(), now), revoked);
        throw new SessionExpiredException();
    }

    private User createGoogleUser(GoogleTokenVerifier.Result verified) {
        User user = new User();
        user.setGoogleId(verified.subject());
        user.setUsername(generateUniqueUsername());
        // passwordHash is left null - a Google-only account authenticates by
        // googleId, never by password. V21 dropped the NOT NULL constraint for
        // exactly this row shape.
        return userRepository.saveAndFlush(user);
    }

    /**
     * A placeholder username for a Google account: "user" plus a random suffix, retried
     * until unique - the same guarantee {@link #register} gets from the caller having
     * already picked an available name.
     * <p>
     * Deliberately says nothing about the person. It used to be derived from the email's
     * local part, and usernames are public and searchable, so signing in with Google
     * published most of your email address to every other user. The client still offers
     * the new user a chance to pick a real one ({@code newAccount}).
     */
    private String generateUniqueUsername() {
        // 36^8 suffixes: a collision is already unlikely, and ten in a row is not a
        // situation worth a smarter loop.
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder candidate = new StringBuilder(GENERATED_USERNAME_PREFIX);
            for (int i = 0; i < GENERATED_USERNAME_SUFFIX_LENGTH; i++) {
                candidate.append(GENERATED_USERNAME_ALPHABET.charAt(
                        random.nextInt(GENERATED_USERNAME_ALPHABET.length())));
            }
            if (!userRepository.existsByUsernameIgnoringCase(candidate.toString())) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException("Could not generate a unique username");
    }

    private AuthResponse issueToken(User user, boolean newAccount) {
        return persistSession(user, jwtUtil.generateRefreshToken(user.getId()), newAccount);
    }

    /**
     * Records the refresh token's row - without it the token is refused on first use - and
     * prunes this user's dead rows while here, which is cheap (one indexed DELETE) and
     * keeps the table from needing a scheduled sweep.
     */
    private AuthResponse persistSession(User user, JwtUtil.IssuedRefreshToken refresh, boolean newAccount) {
        Instant now = Instant.now();
        refreshTokenRepository.deleteStale(user.getId(), now, now.minus(REVOKED_RETENTION));
        refreshTokenRepository.save(RefreshToken.issued(
                refresh.jti(), user.getId(), refresh.issuedAt(), refresh.expiresAt()));

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return new AuthResponse(token, refresh.token(), user.getId(), user.getUsername(), newAccount);
    }
}
