package ge.kcamp.linkup.social.entity;

import ge.kcamp.linkup.social.enums.FriendshipStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "friendships")
public class Friendship {

    @EmbeddedId
    private FriendshipId id;

    @Column(name = "status", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private FriendshipStatus status;

    /**
     * Who initiated this edge. On a PENDING row, this is who's waiting for a response.
     * On a BLOCKED row, this is who applied the block - the first to, if both tried:
     * {@code SocialGraphService.blockUser} never hands it to the other party, since
     * only this user may lift it.
     */
    @Column(name = "requested_by")
    private UUID requestedBy;

    /** When the pair became friends. Null for friendships accepted before V35 recorded it. */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /**
     * Whether user A has muted user B's plans, and the reverse. Directional, and
     * meaningful only while the row is ACCEPTED - see {@code V35__friend_profile.sql}.
     */
    @Column(name = "a_muted_b", nullable = false)
    private boolean mutedByA;

    @Column(name = "b_muted_a", nullable = false)
    private boolean mutedByB;
}
