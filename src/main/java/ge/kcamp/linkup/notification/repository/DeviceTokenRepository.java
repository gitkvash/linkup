package ge.kcamp.linkup.notification.repository;

import ge.kcamp.linkup.notification.entity.DeviceToken;
import ge.kcamp.linkup.notification.entity.DeviceTokenId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, DeviceTokenId> {

    List<DeviceToken> findByIdUserId(UUID userId);

    /**
     * Clears a token from whichever user currently holds it. A token identifies one
     * physical device, so registering it must move it rather than add a second owner -
     * otherwise one user's notifications get pushed to another user's phone.
     *
     * <p>A bulk delete rather than the derived {@code deleteByIdFcmToken}, which Spring
     * Data implements as a SELECT followed by a delete per entity. That read is filtered
     * by {@code device_tokens_select_policy}, which is scoped to the caller - so under the
     * restricted role it found nothing belonging to the previous owner, deleted nothing,
     * and the insert that followed hit the unique index on fcm_token. Signing in as a
     * second user on the same phone returned 409. Issuing the DELETE directly leaves it to
     * {@code device_tokens_delete_policy}, which is deliberately unscoped for exactly
     * this case.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DeviceToken d where d.id.fcmToken = :fcmToken")
    int deleteByFcmToken(@Param("fcmToken") String fcmToken);
}
