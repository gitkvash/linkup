package ge.kcamp.linkup.notification;

/**
 * The swap point between the Observer-pattern eventing (which only knows "notify this
 * user of this thing") and the actual transport (logging today, FCM/SSE composite later).
 */
public interface NotificationDispatcher {

    void dispatch(NotificationMessage message);
}
