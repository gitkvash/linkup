package ge.kcamp.linkup.notification;

import ge.kcamp.linkup.notification.entity.DeviceToken;
import ge.kcamp.linkup.notification.entity.DeviceTokenId;
import ge.kcamp.linkup.notification.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Registering a push token is the one request-scoped operation that legitimately touches
 * another user's row, so it is the one place that asks for the system role by hand.
 *
 * <p>A token identifies a physical device. Signing in as a second person on the same phone
 * has to move the token, not add a second owner - otherwise the first user's notifications
 * are delivered to whoever is holding the phone now, which is the hijacking bug V8's
 * unique index was added to close.
 *
 * <p>Under the restricted role that move is impossible, and not because of the DELETE
 * policy, which is deliberately unscoped. Postgres also applies SELECT policies to an
 * UPDATE or DELETE whose WHERE clause references columns of the table, and
 * {@code device_tokens_select_policy} is scoped to the caller. The previous owner's row is
 * therefore invisible, the delete matches nothing, and the insert that follows trips the
 * unique index: a 409 where the user expected to be signed in.
 *
 * <p>Keeping the SELECT policy narrow is worth more than avoiding this - it is what stops
 * an injected query enumerating which device belongs to whom - so the two methods below
 * run against the owner connection instead. Both take the caller's id from
 * {@link ge.kcamp.linkup.UserContext} rather than from the request body, so
 * nobody can claim a device on someone else's behalf.
 */
@Service
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    /**
     * Not annotated {@code @Transactional} at the caller: the role marker has to be set
     * before the transaction opens, because by the time a transactional method body runs
     * its connection may already have been drawn from the wrong pool.
     */
    @Transactional
    public void claim(UUID userId, String token, String platform) {
        // Flush between the two, or Hibernate may order the insert first and trip the
        // unique index on fcm_token.
        deviceTokenRepository.deleteByFcmToken(token);
        deviceTokenRepository.flush();

        DeviceToken deviceToken = new DeviceToken();
        deviceToken.setId(new DeviceTokenId(userId, token));
        deviceToken.setPlatform(platform);
        deviceTokenRepository.save(deviceToken);
    }

    @Transactional
    public void release(String token) {
        deviceTokenRepository.deleteByFcmToken(token);
    }
}
