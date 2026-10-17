package ge.kcamp.linkup.notification.fcm;

import com.google.firebase.messaging.*;
import ge.kcamp.linkup.notification.NotificationDispatcher;
import ge.kcamp.linkup.notification.NotificationMessage;
import ge.kcamp.linkup.notification.entity.DeviceToken;
import ge.kcamp.linkup.notification.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pushes through FCM. The database is touched in two short transactions - reading the
 * recipient's tokens, and pruning the dead ones afterwards - with the {@code sendEach}
 * round trip between them holding no connection. It used to run entirely inside the
 * listener's transaction, so every push held a pooled connection for as long as
 * Google took to answer.
 */
@Component
public class FcmNotificationDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FcmNotificationDispatcher.class);
    private static final Set<MessagingErrorCode> PRUNABLE_ERRORS =
            Set.of(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT);

    /** The most messages one {@code sendEach} accepts. */
    private static final int MAX_BATCH = 500;

    private final FcmClientProvider clientProvider;
    private final DeviceTokenRepository deviceTokenRepository;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public FcmNotificationDispatcher(
            FcmClientProvider clientProvider,
            DeviceTokenRepository deviceTokenRepository,
            PlatformTransactionManager transactionManager) {
        this.clientProvider = clientProvider;
        this.deviceTokenRepository = deviceTokenRepository;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void dispatch(NotificationMessage message) {
        dispatchAll(List.of(message));
    }

    /**
     * Every recipient's tokens in one read, and one {@code sendEach} per
     * {@value #MAX_BATCH} messages, rather than a read and a round trip to Google per
     * recipient - which is what a group plan's notice cost, one member at a time.
     */
    // firebase-admin deprecates all FCM-registration-token targeting (both
    // MulticastMessage#addAllTokens and Message.Builder#setToken) in favor of
    // Firebase Installation IDs (FIDs). Adopting FIDs would require the client
    // to send a different identifier than the one FirebaseMessaging.getToken()
    // returns today, which is a client+server contract change out of scope
    // here; setToken remains fully functional, so the warning is suppressed
    // rather than worked around.
    @SuppressWarnings("deprecation")
    @Override
    public void dispatchAll(List<NotificationMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        Optional<FirebaseMessaging> client = clientProvider.getClient();
        if (client.isEmpty()) {
            log.debug("FCM not configured, skipping push for {} recipient(s)", messages.size());
            return;
        }

        Set<UUID> recipients = messages.stream()
                .map(NotificationMessage::recipientUserId)
                .collect(Collectors.toSet());
        List<DeviceToken> allTokens = readTransaction.execute(
                status -> deviceTokenRepository.findByIdUserIdIn(recipients));
        if (allTokens == null || allTokens.isEmpty()) {
            return;
        }
        Map<UUID, List<DeviceToken>> tokensByUser = allTokens.stream()
                .collect(Collectors.groupingBy(token -> token.getId().getUserId()));

        // One FCM message per (message, token), with the token it went to kept alongside:
        // pruneInvalidTokens matches responses to tokens by position.
        List<DeviceToken> targets = new ArrayList<>();
        List<Message> outgoing = new ArrayList<>();
        for (NotificationMessage message : messages) {
            List<DeviceToken> tokens = tokensByUser.getOrDefault(message.recipientUserId(), List.of());
            if (tokens.isEmpty()) {
                continue;
            }
            Notification notification = Notification.builder()
                    .setTitle(message.title())
                    .setBody(message.body() == null ? "" : message.body())
                    .build();
            NotificationUrgency urgency = NotificationUrgency.of(message);
            AndroidConfig android = androidConfig(urgency);
            ApnsConfig apns = apnsConfig(urgency);
            for (DeviceToken token : tokens) {
                Message.Builder messageBuilder = Message.builder()
                        .setToken(token.getId().getFcmToken())
                        .setNotification(notification)
                        .setAndroidConfig(android)
                        .setApnsConfig(apns);
                if (message.metadata() != null) {
                    messageBuilder.putAllData(message.metadata());
                }
                // The type, so a push that lands while the app is open can be
                // told apart - an invitation opens an Accept/Decline prompt,
                // other types only refresh the Alerts list.
                messageBuilder.putData("type", message.type());
                outgoing.add(messageBuilder.build());
                targets.add(token);
            }
        }

        // sendEach(List<Message>) is the current (non-deprecated) batch-send API, takes at
        // most 500 messages, and guarantees response order matches the input list, which
        // pruneInvalidTokens relies on.
        List<DeviceToken> dead = new ArrayList<>();
        for (int from = 0; from < outgoing.size(); from += MAX_BATCH) {
            int to = Math.min(from + MAX_BATCH, outgoing.size());
            try {
                BatchResponse response = client.get().sendEach(outgoing.subList(from, to));
                dead.addAll(invalidTokens(targets.subList(from, to), response));
            } catch (FirebaseMessagingException e) {
                log.warn("FCM send failed for {} message(s): {}", to - from, e.getMessage());
            }
        }
        pruneInvalidTokens(dead);
    }

    /**
     * The channel carries the sound (see {@link NotificationUrgency}); the priority only
     * decides whether Doze may hold the message back, which a quiet one can bear.
     */
    private static AndroidConfig androidConfig(NotificationUrgency urgency) {
        return AndroidConfig.builder()
                .setPriority(urgency == NotificationUrgency.LOUD
                        ? AndroidConfig.Priority.HIGH
                        : AndroidConfig.Priority.NORMAL)
                .setNotification(AndroidNotification.builder()
                        .setChannelId(urgency.channelId())
                        .build())
                .build();
    }

    /**
     * iOS has no channels: a push either plays the default sound or arrives silently.
     * Priority 5 lets the system batch a quiet one with other traffic.
     */
    private static ApnsConfig apnsConfig(NotificationUrgency urgency) {
        boolean loud = urgency == NotificationUrgency.LOUD;
        ApnsConfig.Builder builder = ApnsConfig.builder()
                .putHeader("apns-priority", loud ? "10" : "5");
        if (loud) {
            builder.setAps(Aps.builder().setSound("default").build());
        }
        return builder.build();
    }

    private static List<DeviceToken> invalidTokens(List<DeviceToken> tokens, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();
        List<DeviceToken> dead = new ArrayList<>();
        for (int i = 0; i < responses.size() && i < tokens.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (!sendResponse.isSuccessful() && sendResponse.getException() != null
                    && PRUNABLE_ERRORS.contains(sendResponse.getException().getMessagingErrorCode())) {
                dead.add(tokens.get(i));
            }
        }
        return dead;
    }

    private void pruneInvalidTokens(List<DeviceToken> dead) {
        if (dead.isEmpty()) {
            return;
        }
        // Keyed on (user, token) - the row that was read - so a token that moved to
        // another account since the read is left alone.
        writeTransaction.executeWithoutResult(status -> dead.forEach(token ->
                deviceTokenRepository.deleteByUserIdAndFcmToken(
                        token.getId().getUserId(), token.getId().getFcmToken())));
    }
}
