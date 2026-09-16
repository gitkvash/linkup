package ge.kcamp.linkup.identity.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.ZonedDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID id;

    @Column(name = "username", length = 50, unique = true)
    private String username;

    /**
     * What this person calls themselves. Not unique and not an identifier - the username
     * stays both - so every read falls back to the username when it is null, which it is
     * for every account that has never set one.
     */
    @Column(name = "display_name", length = 50)
    private String displayName;

    @Column(name = "bio", length = 160)
    private String bio;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "google_id", unique = true)
    private String googleId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private ZonedDateTime createdAt;
}
