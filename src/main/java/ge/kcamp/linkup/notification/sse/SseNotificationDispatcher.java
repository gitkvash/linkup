package ge.kcamp.linkup.notification.sse;

import ge.kcamp.linkup.notification.NotificationDispatcher;
import ge.kcamp.linkup.notification.NotificationMessage;
import org.springframework.stereotype.Component;

@Component
public class SseNotificationDispatcher implements NotificationDispatcher {

    private final SseEmitterRegistry emitterRegistry;

    public SseNotificationDispatcher(SseEmitterRegistry emitterRegistry) {
        this.emitterRegistry = emitterRegistry;
    }

    @Override
    public void dispatch(NotificationMessage message) {
        emitterRegistry.push(message.recipientUserId(), message);
    }
}
