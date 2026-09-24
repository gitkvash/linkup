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
     * Takes a token away from every account except {@code keepUserId}. A token identifies
     * one physical device, so registering it must move it rather than add a second owner -
     * otherwise one user's notifications get pushed to another user's phone.
     * <p>
     * The only statement {@code DeviceTokenService} runs as the system role, so it is
     * written to be unable to do more than the move requires: it never touches the
     * claiming user's own rows. (It replaced an unscoped delete-by-token, which the
     * sign-out endpoint also used - as the system role - so it could delete anyone's.)
     * <p>
     * A bulk delete rather than a derived {@code deleteBy...}, which Spring Data implements
     * as a SELECT followed by a delete per entity. Under the restricted role that read is
     * filtered by {@code device_tokens_select_policy}, which is scoped to the caller - it
     * found nothing belonging to the previous owner, deleted nothing, and the insert that
     * followed hit the unique index on fcm_token: signing in as a second user on the same
     * phone returned 409.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DeviceToken d where d.id.fcmToken = :fcmToken and d.id.userId <> :keepUserId")
    int deleteByFcmTokenHeldByOthers(
            @Param("fcmToken") String fcmToken, @Param("keepUserId") UUID keepUserId);

    /** One user's own registration of a token. A bulk delete for the same reason as above. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DeviceToken d where d.id.userId = :userId and d.id.fcmToken = :fcmToken")
    int deleteByUserIdAndFcmToken(@Param("userId") UUID userId, @Param("fcmToken") String fcmToken);
}
