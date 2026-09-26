package ge.kcamp.linkup.notification.internal;

import ge.kcamp.linkup.identity.AccountDeletedEvent;
import ge.kcamp.linkup.notification.sse.SseEmitterRegistry;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * What notification still holds for an account after it is deleted. The rows - its
 * notifications and device tokens - went with the account (V33); what is left is in
 * memory, the live streams it had open.
 * <p>
 * {@code NOT_SUPPORTED} like {@link ActivityEventListener}: nothing here touches the
 * database, so there is no reason to hold a connection.
 */
@Component
class AccountEventListener {

    private final SseEmitterRegistry sseEmitterRegistry;

    AccountEventListener(SseEmitterRegistry sseEmitterRegistry) {
        this.sseEmitterRegistry = sseEmitterRegistry;
    }

    /**
     * On this instance only: the registry is in memory, and a stream held by another
     * instance runs out on its own timeout. Either way nothing is pushed to it any more -
     * every notification about the account's plans went with them - and the client has
     * signed out and stopped reconnecting by the time this runs.
     */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void onAccountDeleted(AccountDeletedEvent event) {
        sseEmitterRegistry.closeAll(event.userId());
    }
}
