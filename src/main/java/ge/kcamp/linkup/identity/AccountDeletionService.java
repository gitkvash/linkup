package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Deleting your own account, with everything that belongs to it: the plans you made, the
 * groups you own, your friendships, memberships, notifications, devices and sessions. There
 * is no soft delete and no grace period - the App Store asks for deletion, not deactivation,
 * and nothing here would know what to do with a half-deleted person.
 * <p>
 * The rows are removed by one database function, {@code app_delete_current_account()}
 * (V33), which says what happens to each table and why. It deletes whoever the connection
 * is stamped as, never an id passed in, so this can only ever delete the caller - which is
 * why {@code userId} is only checked against {@link UserContext}, not handed to it.
 * <p>
 * The access token outlives the account by up to its 15 minutes. That is harmless: every
 * refresh token went with the account, so no new one can be minted, and a write with the
 * old one fails on the missing user's foreign key rather than recreating anything.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AccountDeletionService(UserRepository userRepository, ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @return false if there was no such account - already deleted, most likely by a retry
     *         whose first response was lost
     */
    @Transactional
    public boolean deleteOwnAccount(UUID userId) {
        if (!userId.equals(UserContext.getUserId())) {
            // The function would delete the stamped user, not this one. Refusing keeps a
            // caller from believing it deleted somebody it did not.
            throw new IllegalStateException("Account deletion must run as the account being deleted");
        }
        boolean deleted = userRepository.deleteCurrentAccount();
        if (deleted) {
            eventPublisher.publishEvent(new AccountDeletedEvent(userId, Instant.now()));
            log.info("Account {} deleted", userId);
        }
        return deleted;
    }
}
