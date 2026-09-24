package ge.kcamp.linkup;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Points {@code spring.datasource.*} at {@link AbstractIntegrationTest}'s container, for a
 * test outside this package to call from its own {@code @DynamicPropertySource}.
 * <p>
 * {@code @ServiceConnection} hands the container to Boot's owner {@code DataSource} only.
 * {@code DataSourceConfig} builds the app-role pool from the {@code spring.datasource.url}
 * property, which it doesn't override - so without this, the pool every request-path query
 * runs through connects to whatever that property resolves to: the dev database on 5433.
 */
public final class ContainerDatabase {

    private ContainerDatabase() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
    }
}
