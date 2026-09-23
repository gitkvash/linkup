package ge.kcamp.linkup.notification.fcm;

import com.google.firebase.messaging.*;
import ge.kcamp.linkup.notification.NotificationDispatcher;
import ge.kcamp.linkup.notification.NotificationMessage;
import ge.kcamp.linkup.notification.entity.DeviceToken;
import ge.kcamp.linkup.notification.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class FcmNotificationDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FcmNotificationDispatcher.class);
    private static final Set<MessagingErrorCode> PRUNABLE_ERRORS =
            Set.of(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT);

    private final FcmClientProvider clientProvider;
    private final DeviceTokenRepository deviceTokenRepository;

    public FcmNotificationDispatcher(FcmClientProvider clientProvider, DeviceTokenRepository deviceTokenRepository) {
        this.clientProvider = clientProvider;
        this.deviceTokenRepository = deviceTokenRepository;
    }

    // firebase-admin deprecates all FCM-registration-token targeting (both
    // MulticastMessage#addAllTokens and Message.Builder#setToken) in favor of
    // Firebase Installation IDs (FIDs). Adopting FIDs would require the client
    // to send a different identifier than the one FirebaseMessaging.getToken()
    // returns today, which is a client+server contract change out of scope
    // here; setToken remains fully functional, so the warning is suppressed
    // rather than worked around.
    @SuppressWarnings("deprecation")
    @Override
    public void dispatch(NotificationMessage message) {
        Optional<FirebaseMessaging> client = clientProvider.getClient();
        if (client.isEmpty()) {
            log.debug("FCM not configured, skipping push for {}", message.recipientUserId());
            return;
        }

        List<DeviceToken> tokens = deviceTokenRepository.findByIdUserId(message.recipientUserId());
        if (tokens.isEmpty()) {
            return;
        }

        Notification notification = Notification.builder()
                .setTitle(message.title())
                .setBody(message.body() == null ? "" : message.body())
                .build();

        // sendEach(List<Message>) is the current (non-deprecated) batch-send API
        // and guarantees response order matches the input list, which
        // pruneInvalidTokens relies on.
        List<Message> messages = tokens.stream()
                .map(t -> {
                    Message.Builder messageBuilder = Message.builder()
                            .setToken(t.getId().getFcmToken())
                            .setNotification(notification);
                    if (message.metadata() != null) {
                        messageBuilder.putAllData(message.metadata());
                    }
                    // The type, so a push that lands while the app is open can be
                    // told apart - an invitation opens an Accept/Decline prompt,
                    // other types only refresh the Alerts list.
                    messageBuilder.putData("type", message.type());
                    return messageBuilder.build();
                })
                .toList();

        try {
            BatchResponse response = client.get().sendEach(messages);
            pruneInvalidTokens(tokens, response);
        } catch (FirebaseMessagingException e) {
            log.warn("FCM send failed for {}: {}", message.recipientUserId(), e.getMessage());
        }
    }

    private void pruneInvalidTokens(List<DeviceToken> tokens, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size() && i < tokens.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (!sendResponse.isSuccessful() && sendResponse.getException() != null
                    && PRUNABLE_ERRORS.contains(sendResponse.getException().getMessagingErrorCode())) {
                deviceTokenRepository.delete(tokens.get(i));
            }
        }
    }
}
