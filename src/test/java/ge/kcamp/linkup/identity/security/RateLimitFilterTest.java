package ge.kcamp.linkup.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private RateLimitFilter filter(boolean enabled, int authRequests) {
        return new RateLimitFilter(
                enabled,
                new RateLimiter("auth", authRequests, Duration.ofMinutes(1), 1000),
                jsonMapper);
    }

    private static MockHttpServletRequest post(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        return request;
    }

    private static MockHttpServletResponse send(RateLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void authEndpointsShareOnePerIpBudgetAndAnswer429InTheApiErrorShape() throws Exception {
        RateLimitFilter filter = filter(true, 2);

        assertThat(send(filter, post("/api/v1/auth/login", "10.0.0.1")).getStatus()).isEqualTo(200);
        assertThat(send(filter, post("/api/v1/auth/refresh", "10.0.0.1")).getStatus()).isEqualTo(200);
        MockHttpServletResponse refused = send(filter, post("/api/v1/auth/logout", "10.0.0.1"));

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isPositive();
        assertThat(refused.getContentType()).startsWith("application/json");
        JsonNode body = jsonMapper.readTree(refused.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("message").asString()).isEqualTo(RateLimitFilter.MESSAGE);
        assertThat(body.has("timestamp")).isTrue();

        // Another client is unaffected.
        assertThat(send(filter, post("/api/v1/auth/login", "10.0.0.2")).getStatus()).isEqualTo(200);
    }

    @Test
    void otherEndpointsAndMethodsAreNotCounted() throws Exception {
        RateLimitFilter filter = filter(true, 1);

        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, post("/api/v1/activities", "10.0.0.1")).getStatus()).isEqualTo(200);
            MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/auth/login");
            get.setRemoteAddr("10.0.0.1");
            assertThat(send(filter, get).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void disabledFilterLimitsNothing() throws Exception {
        RateLimitFilter filter = filter(false, 1);

        for (int i = 0; i < 3; i++) {
            assertThat(send(filter, post("/api/v1/auth/login", "10.0.0.1")).getStatus()).isEqualTo(200);
        }
    }
}
