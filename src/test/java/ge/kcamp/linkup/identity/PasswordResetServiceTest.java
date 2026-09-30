package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.entity.PasswordResetCode;
import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.exception.InvalidResetCodeException;
import ge.kcamp.linkup.identity.mail.PasswordResetMailer;
import ge.kcamp.linkup.identity.repository.PasswordResetCodeRepository;
import ge.kcamp.linkup.identity.repository.RefreshTokenRepository;
import ge.kcamp.linkup.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The reset flow's decisions against mocked repositories. That the database lets exactly
 * one of two racing guesses take the last attempt is {@code takeAttempt}'s contract,
 * modelled here by it returning 1 or 0; the SQL function is exercised by the identity ITs.
 */
class PasswordResetServiceTest {

    private static final String EMAIL = "nino@example.com";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordResetCodeRepository codes = mock(PasswordResetCodeRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final PasswordResetMailer mailer = mock(PasswordResetMailer.class);
    private final CountingEncoder encoder = new CountingEncoder();

    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(users, codes, refreshTokens, encoder, mailer);
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("nino");
        user.setEmail(EMAIL);
        user.setPasswordHash(encoder.encode("old password"));
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        encoder.encodeCalls.set(0);
        encoder.matchesCalls.set(0);
    }

    // --- requestCode ---------------------------------------------------------------

    @Test
    void requestCodeStoresAHashAndMailsTheCode() {
        when(codes.findById(user.getId())).thenReturn(Optional.empty());

        service.requestCode("  Nino@Example.COM ");

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendResetCode(eq(EMAIL), code.capture(), eq(15));
        assertThat(code.getValue()).matches("\\d{6}");

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expires = ArgumentCaptor.forClass(Instant.class);
        verify(codes).upsert(eq(user.getId()), hash.capture(), now.capture(), expires.capture());
        // Only the hash is stored, and it is the hash of the code that was mailed.
        assertThat(hash.getValue()).isNotEqualTo(code.getValue());
        assertThat(encoder.matches(code.getValue(), hash.getValue())).isTrue();
        assertThat(expires.getValue()).isEqualTo(now.getValue().plus(PasswordResetService.CODE_TTL));
    }

    @Test
    void requestCodeForAnUnknownAddressDoesNothingButCostsTheSame() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.requestCode("nobody@example.com");

        verifyNoInteractions(mailer);
        verify(codes, never()).upsert(any(), anyString(), any(), any());
        // The BCrypt a real request pays, so the response time does not give it away.
        assertThat(encoder.encodeCalls.get()).isEqualTo(1);
    }

    @Test
    void requestCodeForAGoogleOnlyAccountSendsNothing() {
        user.setPasswordHash(null);

        service.requestCode(EMAIL);

        verifyNoInteractions(mailer);
        verify(codes, never()).upsert(any(), anyString(), any(), any());
    }

    @Test
    void requestCodeInsideTheCooldownSendsNothing() {
        PasswordResetCode recent = new PasswordResetCode();
        recent.setUserId(user.getId());
        recent.setCreatedAt(Instant.now().minusSeconds(10));
        when(codes.findById(user.getId())).thenReturn(Optional.of(recent));

        service.requestCode(EMAIL);

        verifyNoInteractions(mailer);
        verify(codes, never()).upsert(any(), anyString(), any(), any());
    }

    @Test
    void requestCodeAfterTheCooldownReplacesTheCode() {
        PasswordResetCode old = new PasswordResetCode();
        old.setUserId(user.getId());
        old.setCreatedAt(Instant.now().minus(PasswordResetService.RESEND_COOLDOWN).minusSeconds(1));
        when(codes.findById(user.getId())).thenReturn(Optional.of(old));

        service.requestCode(EMAIL);

        verify(mailer).sendResetCode(eq(EMAIL), anyString(), anyInt());
        verify(codes).upsert(eq(user.getId()), anyString(), any(), any());
    }

    // --- resetPassword -------------------------------------------------------------

    @Test
    void resetPasswordWithTheRightCodeSetsThePasswordAndSignsEverythingOut() {
        storeCode("123456");
        when(codes.takeAttempt(eq(user.getId()), any(), eq(PasswordResetService.MAX_ATTEMPTS))).thenReturn(1);
        when(users.resetPassword(eq(user.getId()), anyString())).thenReturn(true);

        service.resetPassword(EMAIL, "123456", "brand new password");

        ArgumentCaptor<String> newHash = ArgumentCaptor.forClass(String.class);
        verify(users).resetPassword(eq(user.getId()), newHash.capture());
        assertThat(encoder.matches("brand new password", newHash.getValue())).isTrue();
        verify(refreshTokens).revokeAll(eq(user.getId()), any());
    }

    @Test
    void resetPasswordFindsTheAccountWhateverTheCaseOfTheEmail() {
        storeCode("123456");
        when(codes.takeAttempt(any(), any(), anyInt())).thenReturn(1);
        when(users.resetPassword(any(), anyString())).thenReturn(true);

        service.resetPassword("NINO@example.com ", "123456", "brand new password");

        verify(users).resetPassword(eq(user.getId()), anyString());
    }

    @Test
    void aWrongCodeFailsAndChangesNothing() {
        storeCode("123456");
        when(codes.takeAttempt(any(), any(), anyInt())).thenReturn(1);

        assertThatThrownBy(() -> service.resetPassword(EMAIL, "654321", "brand new password"))
                .isInstanceOf(InvalidResetCodeException.class);

        verify(users, never()).resetPassword(any(), anyString());
        verify(refreshTokens, never()).revokeAll(any(), any());
    }

    @Test
    void theGuessIsSpentBeforeTheCodeIsCompared() {
        storeCode("123456");
        when(codes.takeAttempt(any(), any(), anyInt())).thenReturn(1);

        assertThatThrownBy(() -> service.resetPassword(EMAIL, "000000", "brand new password"))
                .isInstanceOf(InvalidResetCodeException.class);

        verify(codes).takeAttempt(eq(user.getId()), any(), eq(PasswordResetService.MAX_ATTEMPTS));
    }

    @Test
    void aCodeWithNoGuessesLeftFailsEvenWhenTheCodeIsRight() {
        storeCode("123456");
        when(codes.takeAttempt(any(), any(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> service.resetPassword(EMAIL, "123456", "brand new password"))
                .isInstanceOf(InvalidResetCodeException.class);

        verify(users, never()).resetPassword(any(), anyString());
        // The stored hash is not even read, and the comparison still costs one BCrypt.
        verify(codes, never()).findById(any());
        assertThat(encoder.matchesCalls.get()).isEqualTo(1);
    }

    @Test
    void anUnknownEmailFailsTheSameWayAtTheSameCost() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("nobody@example.com", "123456", "brand new password"))
                .isInstanceOf(InvalidResetCodeException.class);

        verify(codes, never()).takeAttempt(any(), any(), anyInt());
        assertThat(encoder.matchesCalls.get()).isEqualTo(1);
    }

    @Test
    void aCodeThatExpiredBetweenTheCheckAndTheUpdateFails() {
        storeCode("123456");
        when(codes.takeAttempt(any(), any(), anyInt())).thenReturn(1);
        when(users.resetPassword(any(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.resetPassword(EMAIL, "123456", "brand new password"))
                .isInstanceOf(InvalidResetCodeException.class);

        verify(refreshTokens, never()).revokeAll(any(), any());
    }

    @Test
    void aPasswordBCryptCannotHashIsRefusedBeforeAnythingIsSpent() {
        String tooLong = "x".repeat(73);

        assertThatThrownBy(() -> service.resetPassword(EMAIL, "123456", tooLong))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(codes);
    }

    private void storeCode(String code) {
        PasswordResetCode row = new PasswordResetCode();
        row.setUserId(user.getId());
        row.setCodeHash(encoder.encode(code));
        row.setCreatedAt(Instant.now());
        row.setExpiresAt(Instant.now().plus(PasswordResetService.CODE_TTL));
        when(codes.findById(user.getId())).thenReturn(Optional.of(row));
        encoder.encodeCalls.set(0);
        encoder.matchesCalls.set(0);
    }

    /** Real BCrypt at the minimum cost, counting calls: the tests assert cost as well as outcome. */
    private static final class CountingEncoder implements PasswordEncoder {
        private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder(4);
        final AtomicInteger encodeCalls = new AtomicInteger();
        final AtomicInteger matchesCalls = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            encodeCalls.incrementAndGet();
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls.incrementAndGet();
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
