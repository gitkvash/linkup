package ge.kcamp.linkup.identity.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import ge.kcamp.linkup.identity.exception.GoogleAuthenticationFailedException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies a Google Sign-In ID token's signature, audience and issuer - the one class
 * that knows about Google's token mechanics, the way {@link JwtUtil} is the one class
 * that knows about ours.
 */
@Component
public class GoogleTokenVerifier {

    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenVerifier(@Value("${linkup.security.google.client-id}") String clientId) {
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    public Result verify(String idToken) {
        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new GoogleAuthenticationFailedException();
        }
        if (token == null) {
            throw new GoogleAuthenticationFailedException();
        }

        GoogleIdToken.Payload payload = token.getPayload();
        return new Result(payload.getSubject(), payload.getEmail());
    }

    public record Result(String subject, String email) {
    }
}
