package ge.kcamp.linkup.identity.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two token kinds share a signing key, so the only thing keeping a months-long
 * refresh token from working as a bearer token is the type claim. These pin that down.
 */
class JwtUtilTest {

    private static final String SECRET = "dGVzdC1vbmx5LWtleS1mb3Itand0LXV0aWwtdGVzdHMtMDEyMzQ1Njc4OQ==";
    private static final long DAY = 86_400_000L;

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, DAY, 180 * DAY, new MockEnvironment());
    private final UUID userId = UUID.randomUUID();

    @Test
    void accessTokenAuthenticatesButCannotRefresh() {
        String access = jwtUtil.generateToken(userId, "nino");

        assertThat(jwtUtil.parseUserId(access)).contains(userId);
        assertThat(jwtUtil.parseRefreshUserId(access)).isEmpty();
    }

    @Test
    void refreshTokenRefreshesButCannotAuthenticate() {
        String refresh = jwtUtil.generateRefreshToken(userId);

        assertThat(jwtUtil.parseRefreshUserId(refresh)).contains(userId);
        assertThat(jwtUtil.parseUserId(refresh)).isEmpty();
    }

    /** Tokens issued before refresh tokens existed carry no type claim and stay valid. */
    @Test
    void untypedLegacyTokenIsAnAccessToken() {
        String legacy = Jwts.builder()
                .setSubject(userId.toString())
                .claim("username", "nino")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + DAY))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(jwtUtil.parseUserId(legacy)).contains(userId);
        assertThat(jwtUtil.parseRefreshUserId(legacy)).isEmpty();
    }

    @Test
    void expiredRefreshTokenIsRefused() {
        JwtUtil shortLived = new JwtUtil(SECRET, DAY, -1_000L, new MockEnvironment());

        assertThat(shortLived.parseRefreshUserId(shortLived.generateRefreshToken(userId))).isEmpty();
    }
}
