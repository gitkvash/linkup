package ge.kcamp.linkup.notification;

import java.util.List;

/**
 * The swap point between the Observer-pattern eventing (which only knows "notify this
 * user of this thing") and the actual transport (logging today, FCM/SSE composite later).
 */
public interface NotificationDispatcher {

    void dispatch(NotificationMessage message);

    /**
     * Several messages at once - one event's worth, for many recipients. One at a time
     * unless a transport can do better: FCM sends the lot in one {@code sendEach}.
     */
    default void dispatchAll(List<NotificationMessage> messages) {
        messages.forEach(this::dispatch);
    }
}
