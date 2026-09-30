package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.entity.PasswordResetCode;
import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.exception.InvalidResetCodeException;
import ge.kcamp.linkup.identity.mail.PasswordResetMailer;
import ge.kcamp.linkup.identity.repository.PasswordResetCodeRepository;
import ge.kcamp.linkup.identity.repository.RefreshTokenRepository;
import ge.kcamp.linkup.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Forgotten password: an emailed six-digit code, then a new password.
 * <p>
 * Nothing here says whether an account exists. {@link #requestCode} returns the same way
 * for a real email, an unknown one and a Google-only account, and does one BCrypt on every
 * path so the time does not say either; {@link #resetPassword} has one failure for all
 * causes and one BCrypt comparison on every path to it. This is
 * {@link IdentityService#login}'s discipline, for the same reason.
 * <p>
 * Six digits are only safe with a guess limit, and there are two: five wrong tries kill
 * the code (see {@link PasswordResetCodeRepository#takeAttempt}), and the per-IP limit in
 * {@code RateLimitFilter} bounds how many codes one client can request or try.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(15);

    /**
     * Asking again inside this window sends nothing. Without it the endpoint is a mail
     * cannon aimed at one inbox, and each request replaces the code, so a person who had
     * just received one would find it dead. Silent, like everything else here.
     */
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** Wrong guesses a code survives. A million codes, five tries. */
    static final int MAX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository codes;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetMailer mailer;
    private final SecureRandom random = new SecureRandom();

    /** What a comparison runs against when there is no real code hash. See {@link IdentityService}. */
    private final String dummyCodeHash;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetCodeRepository codes,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            PasswordResetMailer mailer) {
        this.userRepository = userRepository;
        this.codes = codes;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.dummyCodeHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Emails a fresh code if {@code email} belongs to an account with a password. Returns
     * normally in every case.
     */
    @Transactional
    public void requestCode(String email) {
        // First, and on every path: the one BCrypt that real requests pay, so that "no such
        // account" is not the fast answer.
        String code = String.format("%06d", random.nextInt(1_000_000));
        String codeHash = passwordEncoder.encode(code);

        String normalized = EmailAddress.normalize(email);
        Optional<User> user = normalized == null ? Optional.empty() : userRepository.findByEmail(normalized);
        if (user.isEmpty() || user.get().getPasswordHash() == null) {
            // Unknown address, or a Google-only account: there is no password to reset.
            return;
        }

        UUID userId = user.get().getId();
        Instant now = Instant.now();
        Optional<PasswordResetCode> existing = codes.findById(userId);
        if (existing.isPresent() && existing.get().getCreatedAt().isAfter(now.minus(RESEND_COOLDOWN))) {
            return;
        }

        codes.upsert(userId, codeHash, now, now.plus(CODE_TTL));
        sendAfterCommit(normalized, code);
    }

    /**
     * Sets a new password if {@code code} is the live code for {@code email}. On success the
     * code is spent and every session of the account is revoked, so whoever held the old
     * password - or a stolen refresh token - is signed out. The caller then signs in with
     * the new password: no session is issued here.
     * <p>
     * {@code noRollbackFor}: a wrong guess has to stay counted. Rolling back on the
     * exception would undo the attempt and make the limit decoration.
     *
     * @throws InvalidResetCodeException for every kind of failure
     */
    @Transactional(noRollbackFor = InvalidResetCodeException.class)
    public void resetPassword(String email, String code, String newPassword) {
        IdentityService.requirePasswordFitsBcrypt(newPassword);

        String normalized = EmailAddress.normalize(email);
        Optional<User> user = normalized == null ? Optional.empty() : userRepository.findByEmail(normalized);

        // The guess is spent before the code is compared - see takeAttempt for why.
        UUID userId = user.map(User::getId).orElse(null);
        Instant now = Instant.now();
        String storedHash = null;
        if (userId != null && codes.takeAttempt(userId, now, MAX_ATTEMPTS) == 1) {
            storedHash = codes.findById(userId).map(PasswordResetCode::getCodeHash).orElse(null);
        }

        // One BCrypt comparison whatever happened above.
        boolean matches = passwordEncoder.matches(code, storedHash != null ? storedHash : dummyCodeHash);
        if (storedHash == null || !matches) {
            throw new InvalidResetCodeException();
        }

        // Spends the code and sets the hash in one statement; false only if the code expired
        // or was replaced between the comparison and here.
        if (!userRepository.resetPassword(userId, passwordEncoder.encode(newPassword))) {
            throw new InvalidResetCodeException();
        }
        int revoked = refreshTokens.revokeAll(userId, now);
        log.info("Password reset for user {}; revoked {} session(s)", userId, revoked);
    }

    /**
     * Only once the row is committed: a mail that went out before a rollback would carry a
     * code that was never stored, and there is no telling that apart from a wrong one.
     */
    private void sendAfterCommit(String toEmail, String code) {
        int minutes = (int) CODE_TTL.toMinutes();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mailer.sendResetCode(toEmail, code, minutes);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mailer.sendResetCode(toEmail, code, minutes);
            }
        });
    }
}
