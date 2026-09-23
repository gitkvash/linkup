package ge.kcamp.linkup.identity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Component
public class JwtUtil {

    /** HS256 needs at least 256 bits of key material. */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * The dev key from {@code application-dev.yaml}, blocklisted so it can't be carried
     * into a real environment through an env var. An earlier revision shipped a working
     * default in the main config, which meant a deployment that forgot to set
     * LINKUP_JWT_SECRET signed tokens with a key published in this repository - enough
     * to forge a token for any user id.
     */
    private static final String DEV_ONLY_SECRET =
            "ZGV2LW9ubHkta2V5LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi1saW5rdXAtMDE=";

    private final Key key;
    /**
     * Built once and reused. {@code Jwts.parserBuilder()...build()} allocated a fresh
     * parser for every call, and the authentication filter made two of those per request.
     * A parser is immutable and thread-safe once built.
     */
    private final JwtParser parser;
    private final long expirationMs;
    private final long refreshExpirationMs;

    /**
     * Which of the two kinds a token is. Both are signed with the same key, so without
     * this a refresh token - which lives for months - would be accepted as a bearer
     * token on every endpoint. A token with no claim at all predates refresh tokens and
     * is an access token.
     */
    private static final String TYPE_CLAIM = "typ";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";

    /**
     * @param refreshExpirationMs how long a refresh token lasts. Every refresh hands back
     *                            a new one, so the window slides: a person who opens the
     *                            app at least once in that time is never signed out. The
     *                            access token stays short so a leaked one expires quickly.
     */
    public JwtUtil(
            @Value("${linkup.security.jwt.secret}") String base64Secret,
            @Value("${linkup.security.jwt.expiration-ms:86400000}") long expirationMs,
            @Value("${linkup.security.jwt.refresh-expiration-ms:15552000000}") long refreshExpirationMs,
            Environment environment) {

        byte[] secret = decodeAndValidate(base64Secret, environment);
        this.key = Keys.hmacShaKeyFor(secret);
        this.parser = Jwts.parserBuilder().setSigningKey(this.key).build();
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    private static byte[] decodeAndValidate(String base64Secret, Environment environment) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException(
                    "linkup.security.jwt.secret is not set. Set the LINKUP_JWT_SECRET environment "
                            + "variable to a base64-encoded key of at least " + MIN_SECRET_BYTES + " bytes.");
        }

        if (DEV_ONLY_SECRET.equals(base64Secret.strip()) && !isDevProfile(environment)) {
            throw new IllegalStateException(
                    "Refusing to start: LINKUP_JWT_SECRET is the development key, which is public. "
                            + "Generate a unique key per environment.");
        }

        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(base64Secret.strip());
        } catch (RuntimeException e) {
            throw new IllegalStateException("linkup.security.jwt.secret must be valid base64.", e);
        }

        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "linkup.security.jwt.secret decodes to " + decoded.length + " bytes; at least "
                            + MIN_SECRET_BYTES + " are required.");
        }
        return decoded;
    }

    /** True when running locally: {@code dev} is active, or nothing is and it's the default. */
    private static boolean isDevProfile(Environment environment) {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            return Arrays.asList(environment.getDefaultProfiles()).contains("dev");
        }
        return Arrays.asList(active).contains("dev");
    }

    public String generateToken(UUID userId, String username) {
        return Jwts.builder()
                .setSubject(userId.toString())
                .claim("username", username)
                .claim(TYPE_CLAIM, ACCESS)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    /** Only good for {@code POST /auth/refresh}; {@link #parseUserId} refuses it. */
    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
                .setSubject(userId.toString())
                .claim(TYPE_CLAIM, REFRESH)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
                .signWith(key)
                .compact();
    }

    public UUID getUserIdFromToken(String token) {
        return UUID.fromString(getClaimFromToken(token, Claims::getSubject));
    }

    /**
     * Validate and read the subject in one pass.
     * <p>
     * The authentication filter used to call {@link #validateToken} and then
     * {@link #getUserIdFromToken}, which verified the HMAC and re-parsed the JSON twice
     * for every authenticated request - all of it on the request thread, before any
     * handler ran. Empty means the token is absent, expired, malformed or not signed by
     * this key; the caller cannot tell those apart, which is deliberate.
     */
    public Optional<UUID> parseUserId(String token) {
        return parseSubject(token, false);
    }

    /** The user a refresh token was issued to, if it is one, valid and unexpired. */
    public Optional<UUID> parseRefreshUserId(String token) {
        return parseSubject(token, true);
    }

    private Optional<UUID> parseSubject(String token, boolean refresh) {
        try {
            Claims claims = parser.parseClaimsJws(token).getBody();
            boolean isRefresh = REFRESH.equals(claims.get(TYPE_CLAIM, String.class));
            if (isRefresh != refresh) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = parser.parseClaimsJws(token).getBody();
        return claimsResolver.apply(claims);
    }

    public boolean validateToken(String token) {
        try {
            parser.parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
