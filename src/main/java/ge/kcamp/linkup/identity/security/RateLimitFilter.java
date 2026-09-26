package ge.kcamp.linkup.identity.security;

import ge.kcamp.linkup.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Per-client limits on the endpoints worth guessing at: the {@code /auth} endpoints, by
 * client IP (password guessing, account creation, token probing).
 * <p>
 * The client IP is {@link HttpServletRequest#getRemoteAddr()}, which is only the real
 * client because {@code server.forward-headers-strategy: native} lets Tomcat take it from
 * Render's proxy header. Without that every request would share the proxy's address, and
 * one bucket.
 * <p>
 * The per-username login limit is not here: reading the username means reading the body,
 * which a filter can only do by consuming it before the controller does. It is
 * {@link LoginAttemptLimiter}, called from the login itself.
 * <p>
 * Deliberately not a {@code @Component}. Spring Boot registers every {@code Filter} bean
 * with the servlet container as well, so it would also run outside the security chain,
 * and a limited request would be counted twice. {@link SecurityConfig} builds it and
 * places it after {@link JwtAuthenticationFilter}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Too many requests. Please wait a moment and try again.";

    private final RequestMatcher authEndpoints = new OrRequestMatcher(
            post("/api/v1/auth/login"),
            post("/api/v1/auth/register"),
            post("/api/v1/auth/google"),
            post("/api/v1/auth/refresh"),
            post("/api/v1/auth/logout"));

    private final boolean enabled;
    private final RateLimiter authPerIp;
    private final JsonMapper jsonMapper;

    public RateLimitFilter(
            boolean enabled, RateLimiter authPerIp, JsonMapper jsonMapper) {
        this.enabled = enabled;
        this.authPerIp = authPerIp;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        RateLimiter.Decision decision = RateLimiter.Decision.ALLOWED;
        if (authEndpoints.matches(request)) {
            decision = authPerIp.tryAcquire(request.getRemoteAddr());
        }

        if (!decision.allowed()) {
            writeTooManyRequests(response, decision.retryAfterSeconds());
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * The same body {@code ApiError} gives every other error. Written by hand because a
     * filter answers before any {@code @ControllerAdvice} could.
     */
    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        Object body = ApiError.of(HttpStatus.TOO_MANY_REQUESTS, MESSAGE, ApiError.RATE_LIMITED).getBody();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }

    private static RequestMatcher post(String path) {
        return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path);
    }
}
