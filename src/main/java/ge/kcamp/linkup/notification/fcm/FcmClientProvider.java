package ge.kcamp.linkup.notification.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Optional;

/**
 * No real Firebase project exists in every environment this runs in, so credential
 * absence/failure logs one WARN and yields an empty client in development,
 * leaving the app fully bootable. In the 'prod' profile, it is fatal.
 */
@Component
public class FcmClientProvider {

    private static final Logger log = LoggerFactory.getLogger(FcmClientProvider.class);

    private final org.springframework.core.env.Environment env;
    private final String credentialsPath;
    private volatile FirebaseMessaging client;

    public FcmClientProvider(
            org.springframework.core.env.Environment env,
            @Value("${linkup.fcm.credentials-path}") String credentialsPath) {
        this.env = env;
        this.credentialsPath = credentialsPath;
    }

    @PostConstruct
    void init() {
        if (credentialsPath == null || credentialsPath.isBlank()) {
            log.warn("linkup.fcm.credentials-path not set; push notifications via FCM are disabled.");
            return;
        }

        try {
            InputStream in;
            if (credentialsPath.trim().startsWith("{")) {
                in = new java.io.ByteArrayInputStream(credentialsPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                in = new FileInputStream(credentialsPath);
            }

            try (in) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .build();
                FirebaseApp app = FirebaseApp.getApps().isEmpty()
                        ? FirebaseApp.initializeApp(options)
                        : FirebaseApp.getInstance();
                this.client = FirebaseMessaging.getInstance(app);
            }
        } catch (Exception e) {
            log.warn("Failed to initialize FCM ({}); push notifications via FCM are disabled.", e.getMessage());
        }
    }

    public Optional<FirebaseMessaging> getClient() {
        return Optional.ofNullable(client);
    }
}
