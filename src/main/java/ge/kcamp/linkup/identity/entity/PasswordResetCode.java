package ge.kcamp.linkup.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The live reset code of one account: a BCrypt hash, never the code itself, and how many
 * wrong guesses it has absorbed. At most one row per account - asking for another code
 * replaces it. Written only through {@code PasswordResetCodeRepository}'s statements. See V37.
 */
@Getter
@Setter
@Entity
@Table(name = "password_reset_codes")
public class PasswordResetCode {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
}
