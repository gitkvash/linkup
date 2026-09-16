package ge.kcamp.linkup.social.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@EqualsAndHashCode
@Embeddable
public class FriendshipId implements Serializable {
    @Column(name = "user_a_id")
    private UUID userAId;

    @Column(name = "user_b_id")
    private UUID userBId;
}
