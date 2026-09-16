package ge.kcamp.linkup.social.entity;

import ge.kcamp.linkup.social.enums.FriendshipStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
     * On a BLOCKED row, this is (loosely) who applied the block.
     */
    @Column(name = "requested_by")
    private UUID requestedBy;
}
