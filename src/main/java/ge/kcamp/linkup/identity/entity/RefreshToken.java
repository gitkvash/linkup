package ge.kcamp.linkup.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * The server-side half of a refresh token: whether it has been used or revoked. The token
 * string itself is never stored - the id is its {@code jti}, and the signature is what
 * makes the token worth anything. See V28.
 * <p>
 * {@link Persistable} because the id is assigned, not generated. Without it Spring Data
 * sees a non-null id, assumes the row exists and merges - a SELECT before every INSERT,
 * on the login and refresh paths.
 */
@Getter
@Setter
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken implements Persistable<UUID> {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** The token this one was rotated into. Null for a live token or a sign-out. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean fresh = true;

    public static RefreshToken issued(UUID jti, UUID userId, Instant issuedAt, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setId(jti);
        token.setUserId(userId);
        token.setIssuedAt(issuedAt);
        token.setExpiresAt(expiresAt);
        return token;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        fresh = false;
    }
}
