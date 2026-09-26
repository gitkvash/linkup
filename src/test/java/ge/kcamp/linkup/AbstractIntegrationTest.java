package ge.kcamp.linkup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * <p>
 * The containers are started once per JVM, in a static initialiser, rather than by
 * JUnit's {@code @Container}. That extension stops and restarts static containers for
 * every test class, while Spring caches one application context across classes - so every
 * class after the first ran against a context still pointing at the first class's
 * stopped container, and failed on "connection refused" after Hikari's timeouts. Testcontainers'
 * Ryuk removes them when the JVM exits. Tag filtering reads the annotation without
 * initialising the class, so a run that excludes {@code container} starts nothing.
 * <p>
 * Not {@code @Transactional}, and subclasses shouldn't be either. {@code RlsDataSource}
 * stamps the caller's id on a connection when it is borrowed, and a test-managed
 * transaction borrows one before the test body has set {@link UserContext}: every write
 * then runs as nobody and fails the RLS insert policies. Tests call {@link #actAs} before
 * each call instead, as a request would, and {@link #deleteTestUsersPlans} does the
 * cleanup a rollback used to.
 */
@Tag("container")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:15-3.3").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("linkup")
            .withUsername("linkup")
            .withPassword("password");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /**
     * {@code @ServiceConnection} hands the container to Boot's owner {@code DataSource}
     * only. {@code DataSourceConfig} builds the app-role pool - the one every request-path
     * query runs through - from the {@code spring.datasource.url} property, which it
     * doesn't override. Without this, that pool connected to whatever the property
     * resolved to: the docker-compose dev database on 5433, or nothing at all in CI.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate integrationJdbc;

    private final List<UUID> testUsers = new ArrayList<>();

    /**
     * A real user row - activities, friendships and groups all reference {@code users} -
     * written as the owner, since no one is signed in to create it.
     */
    protected UUID newUser() {
        UUID id = UUID.randomUUID();
        DatabaseRole.runAsSystem(() -> integrationJdbc.update(
                "INSERT INTO users (user_id, username, password_hash) VALUES (?, ?, 'x')",
                id, "it_" + id.toString().substring(0, 12)));
        testUsers.add(id);
        return id;
    }

    /** Who the next borrowed app-role connection is stamped as - the request's user. */
    protected static UUID actAs(UUID userId) {
        UserContext.setUserId(userId);
        return userId;
    }

    /**
     * The plans this test's users made, so they don't show up on the next test's map. The
     * users themselves stay: their usernames are random, and friendships, groups and
     * notifications would all have to go first.
     */
    @AfterEach
    void deleteTestUsersPlans() {
        UserContext.clear();
        DatabaseRole.runAsSystem(() -> testUsers.forEach(id ->
                integrationJdbc.update("DELETE FROM activities WHERE creator_id = ?", id)));
        testUsers.clear();
    }
}
