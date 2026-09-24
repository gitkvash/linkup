package ge.kcamp.linkup.notification;

import ge.kcamp.linkup.DatabaseRole;
import ge.kcamp.linkup.notification.entity.DeviceToken;
import ge.kcamp.linkup.notification.entity.DeviceTokenId;
import ge.kcamp.linkup.notification.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Registering a push token is the one request-scoped operation that legitimately touches
 * another user's row, so it is the one place that asks for the system role by hand - for
 * exactly one statement.
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
 * an injected query enumerating which device belongs to whom - so the one statement that
 * needs to see past it runs against the owner connection instead. Everything else, the
 * caller's own row included, stays on the application role and under its policies. Both
 * methods take the caller's id from {@link ge.kcamp.linkup.UserContext} (via the
 * controller) rather than from the request body, so nobody can claim or release a device
 * on someone else's behalf.
 *
 * <p>Transactions are opened here with a {@link TransactionTemplate} rather than by
 * {@code @Transactional}, because the role marker has to be set before a transaction
 * opens: by the time a transactional method body runs, its connection has already been
 * drawn from one pool or the other.
 */
@Service
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final TransactionTemplate transactions;

    public DeviceTokenService(
            DeviceTokenRepository deviceTokenRepository, PlatformTransactionManager transactionManager) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Two transactions, not one: they run on different connection pools. The first commits
     * before the second starts, so a failure in between leaves the token unowned - the
     * previous account stops getting pushes on a phone it has left, which is the point -
     * and the client registers again on its next start.
     */
    public void claim(UUID userId, String token, String platform) {
        // SYSTEM, for this statement only: take the token off any other account. Scoped to
        // other accounts so it cannot touch the caller's own rows, which are the app
        // role's business below.
        DatabaseRole.runAsSystem(() -> transactions.executeWithoutResult(
                status -> deviceTokenRepository.deleteByFcmTokenHeldByOthers(token, userId)));

        // APP, under the policies: replace the caller's own row. Delete-then-insert rather
        // than an update so updated_at is the database's now(), as it was before.
        transactions.executeWithoutResult(status -> {
            // Flush between the two, or Hibernate may order the insert first and trip the
            // unique index on fcm_token.
            deviceTokenRepository.deleteByUserIdAndFcmToken(userId, token);
            deviceTokenRepository.flush();

            DeviceToken deviceToken = new DeviceToken();
            deviceToken.setId(new DeviceTokenId(userId, token));
            deviceToken.setPlatform(platform);
            deviceTokenRepository.save(deviceToken);
        });
    }

    /**
     * Sign-out: releases the token from the caller's account only. It used to run as the
     * system role and delete by token alone, so anyone signed in could stop any device's
     * pushes by naming its token. The role alone would now scope it (the SELECT policy
     * applies to the DELETE's WHERE), and the explicit user id keeps it scoped if this
     * ever runs somewhere the policies don't apply.
     */
    public void release(UUID userId, String token) {
        transactions.executeWithoutResult(
                status -> deviceTokenRepository.deleteByUserIdAndFcmToken(userId, token));
    }
}
