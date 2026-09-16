package ge.kcamp.linkup;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real Postgres+PostGIS and Redis instead of a
 * manually-run local database. Redis is wired via {@code @DynamicPropertySource} rather
 * than {@code @ServiceConnection} because Spring Boot's built-in service-connection
 * support for a bare {@code GenericContainer("redis:...")} is not guaranteed across
 * versions - this approach works regardless.
 * <p>
 * Tagged {@code container} and excluded from {@code mvn test} by default (see
 * {@code surefire.excluded.groups} in the POM); CI clears that property to run them.
 * Without this the whole test phase aborted on a machine with no Docker daemon, hiding
 * the results of every test that doesn't need one. A tag is used rather than a runtime
 * {@code @EnabledIf} because tag filtering happens at discovery, before anything can
 * probe for Docker while building the Spring test context.
 */
@Tag("container")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:15-3.3").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("linkup")
            .withUsername("linkup")
            .withPassword("password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
