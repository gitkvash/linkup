package ge.kcamp.linkup.identity.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    /**
     * The limits are read here rather than in the filter because the filter must not be a
     * bean - see {@link RateLimitFilter}. {@code linkup.rate-limit.enabled=false} is for
     * scripted runs such as the {@code verify-*.sh} checks, which sign in more often than
     * any person would; nothing outside a local machine should set it.
     */
    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JsonMapper jsonMapper,
            @Value("${linkup.rate-limit.enabled:true}") boolean rateLimitEnabled,
            @Value("${linkup.rate-limit.auth-per-ip.requests:20}") int authRequests,
            @Value("${linkup.rate-limit.auth-per-ip.window-seconds:60}") long authWindowSeconds,
            @Value("${linkup.rate-limit.from-text-per-user.requests:10}") int fromTextRequests,
            @Value("${linkup.rate-limit.from-text-per-user.window-seconds:60}") long fromTextWindowSeconds,
            @Value("${linkup.rate-limit.max-keys:100000}") int maxKeys) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = new RateLimitFilter(
                rateLimitEnabled,
                new RateLimiter("auth-per-ip", authRequests, Duration.ofSeconds(authWindowSeconds), maxKeys),
                new RateLimiter("from-text-per-user", fromTextRequests,
                        Duration.ofSeconds(fromTextWindowSeconds), maxKeys),
                jsonMapper);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                // Container ERROR dispatches have to stay open. OncePerRequestFilter skips the JWT
                // filter on them, so an authenticated request that throws would be re-authorized as
                // anonymous on its way to /error - turning every 500 into an empty-bodied 403 and
                // hiding the actual failure from the client.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Includes /auth/logout: signing out takes the refresh token in the body, not
                // an access token, so it still works after the access token has lapsed.
                .requestMatchers("/api/v1/auth/**").permitAll()
                // Health only. Opening all of /actuator/** left /actuator/prometheus and
                // /actuator/metrics readable by anyone - a URI inventory, request counts
                // and JVM internals, unauthenticated.
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated()
            )
            // Without an explicit entry point a missing/expired token yields 403, which clients
            // can't tell apart from a genuine permission problem. 401 lets them re-authenticate.
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // After the JWT filter, so the from-text limit can key on the authenticated user,
            // and before authorization, so a throttled request costs nothing further.
            .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
