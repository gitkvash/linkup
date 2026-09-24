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
        assertThat(jwtUtil.parseRefreshToken(access)).isEmpty();
    }

    @Test
    void refreshTokenRefreshesButCannotAuthenticate() {
        JwtUtil.IssuedRefreshToken refresh = jwtUtil.generateRefreshToken(userId);

        assertThat(jwtUtil.parseRefreshToken(refresh.token()))
                .contains(new JwtUtil.RefreshClaims(userId, refresh.jti()));
        assertThat(jwtUtil.parseUserId(refresh.token())).isEmpty();
    }

    /** The jti is the key of the server-side row; two tokens must never share one. */
    @Test
    void everyRefreshTokenHasItsOwnJti() {
        JwtUtil.IssuedRefreshToken first = jwtUtil.generateRefreshToken(userId);
        JwtUtil.IssuedRefreshToken second = jwtUtil.generateRefreshToken(userId);

        assertThat(first.jti()).isNotEqualTo(second.jti());
        assertThat(first.expiresAt()).isAfter(first.issuedAt());
    }

    /**
     * Issued before refresh tokens were revocable: no jti, so no row to check or revoke.
     * Refused, at the cost of one sign-in, rather than honoured for up to six months.
     */
    @Test
    void refreshTokenWithoutJtiIsRefused() {
        String legacy = Jwts.builder()
                .setSubject(userId.toString())
                .claim("typ", "refresh")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + DAY))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(jwtUtil.parseRefreshToken(legacy)).isEmpty();
    }

    @Test
    void refreshTokenWithMalformedJtiIsRefusedNotThrown() {
        String odd = Jwts.builder()
                .setSubject(userId.toString())
                .setId("not-a-uuid")
                .claim("typ", "refresh")
                .setExpiration(new Date(System.currentTimeMillis() + DAY))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(jwtUtil.parseRefreshToken(odd)).isEmpty();
        assertThat(jwtUtil.parseRefreshToken(null)).isEmpty();
        assertThat(jwtUtil.parseRefreshToken("garbage")).isEmpty();
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
        assertThat(jwtUtil.parseRefreshToken(legacy)).isEmpty();
    }

    @Test
    void expiredRefreshTokenIsRefused() {
        JwtUtil shortLived = new JwtUtil(SECRET, DAY, -1_000L, new MockEnvironment());

        assertThat(shortLived.parseRefreshToken(shortLived.generateRefreshToken(userId).token())).isEmpty();
    }
}
