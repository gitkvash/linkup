package ge.kcamp.linkup;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * Two connection pools behind one {@link DataSource} bean.
 *
 * <p>Everything shares a single {@code EntityManagerFactory}, so which Postgres login a
 * repository ends up using cannot be decided by injecting a different bean - it has to be
 * decided when the connection is handed out. Hence {@link AbstractRoutingDataSource}:
 * one primary bean, one persistence unit, one transaction manager, and a per-thread marker
 * ({@link DatabaseRole}) that selects the pool.
 *
 * <ul>
 *   <li><b>app</b> - {@code linkup_app}. Restricted, subject to every row-level policy.
 *       Serves requests. Each connection is stamped with the caller's id before use.</li>
 *   <li><b>system</b> - the schema owner, exempt from policies. Background threads only.</li>
 * </ul>
 *
 * <p>Flyway does not go through either: it is configured with its own connection under
 * {@code spring.flyway.*} so migrations keep running as the owner. That also breaks the
 * chicken-and-egg on a fresh database, where {@code linkup_app} does not exist until V17
 * has run - Hikari builds its pool lazily on first use, which is after migration.
 *
 * <p>To roll the whole thing back, point {@code LINKUP_APP_DB_USERNAME} /
 * {@code LINKUP_APP_DB_PASSWORD} at the owner. Both pools become the same login, the owner
 * exemption applies again, and the policies go back to being inert.
 */
@Configuration(proxyBeanMethods = false)
class DataSourceConfig {

    // The owner connection comes from Boot's own spring.datasource binding - that
    // DataSourceProperties bean is registered unconditionally, so declaring a second one
    // here just makes the injection point ambiguous. It is the template for both pools:
    // the system pool uses it as-is, the app pool overrides the credentials.

    @Bean
    @ConfigurationProperties("linkup.datasource.app")
    AppDataSourceProperties appDataSourceProperties() {
        return new AppDataSourceProperties();
    }

    @Bean(destroyMethod = "close")
    HikariDataSource systemDataSource(DataSourceProperties owner) {
        HikariDataSource dataSource = owner.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("linkup-system");
        // Background work only, and it is not on any latency path.
        dataSource.setMaximumPoolSize(5);
        return dataSource;
    }

    @Bean(destroyMethod = "close")
    HikariDataSource appDataSource(
            DataSourceProperties owner, AppDataSourceProperties app, Environment environment) {

        requireAppCredentials(app, environment);

        HikariDataSource dataSource = owner.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .username(app.getUsername())
                .password(app.getPassword())
                .build();
        dataSource.setPoolName("linkup-app");
        dataSource.setMaximumPoolSize(app.getMaxPoolSize());
        return dataSource;
    }

    @Bean
    @Primary
    DataSource dataSource(
            @Qualifier("appDataSource") HikariDataSource app,
            @Qualifier("systemDataSource") HikariDataSource system) {

        DataSource rlsApp = new RlsDataSource(app);

        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return DatabaseRole.current();
            }
        };
        routing.setTargetDataSources(Map.of(
                DatabaseRole.APP, rlsApp,
                DatabaseRole.SYSTEM, system));
        routing.setDefaultTargetDataSource(rlsApp);
        routing.afterPropertiesSet();
        return routing;
    }

    /**
     * Replaces Boot's auto-configured executor purely to attach the decorator - and Boot
     * backs off, because its own bean is conditional on no {@code Executor} being defined.
     * Both names are declared so {@code @Async} resolution finds it the way it would have
     * found Boot's.
     *
     * <p>Every {@code @ApplicationModuleListener} in the application runs here, so this
     * one bean is what puts notification dispatch and feed fan-out on the system role.
     */
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("linkup-async-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setTaskDecorator(systemRoleDecorator());
        return executor;
    }

    private static TaskDecorator systemRoleDecorator() {
        return runnable -> () -> DatabaseRole.runAsSystem(runnable);
    }

    /**
     * Fails startup rather than falling back to the owner. Mirrors the JWT secret check:
     * a deployment that silently ran the whole application as a superuser would look
     * completely healthy, which is the worst possible outcome for a security control.
     */
    private static void requireAppCredentials(AppDataSourceProperties app, Environment environment) {
        boolean dev = Arrays.asList(environment.getActiveProfiles()).contains("dev")
                || (environment.getActiveProfiles().length == 0
                    && Arrays.asList(environment.getDefaultProfiles()).contains("dev"));
        if (dev) {
            return;
        }
        if (app.getUsername() == null || app.getUsername().isBlank()
                || app.getPassword() == null || app.getPassword().isBlank()) {
            throw new IllegalStateException(
                    "linkup.datasource.app.username/password must be set outside the dev profile. "
                    + "This is the restricted role the application serves requests as; without it "
                    + "the application would connect as the schema owner and every row-level "
                    + "security policy would be bypassed.");
        }
    }

    static class AppDataSourceProperties {
        private String username;
        private String password;
        private int maxPoolSize = 10;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }
    }
}

/**
 * Stamps every connection handed out of the app pool with the caller's id, which is what
 * {@code app_current_user()} - and therefore every policy - reads.
 */
class RlsDataSource extends DelegatingDataSource {

    /**
     * Session scope, not transaction scope: the connection is stamped when it is borrowed,
     * before any transaction begins. Always written, never RESET - RESET restores a custom
     * GUC to the empty string rather than NULL, and {@code app_current_user()} is
     * NULLIF-guarded precisely so that both spellings of "nobody" behave the same.
     */
    private static final String SET_CURRENT_USER =
            "SELECT set_config('app.current_user_id', ?, false)";

    RlsDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return stamp(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return stamp(super.getConnection(username, password));
    }

    private static Connection stamp(Connection connection) throws SQLException {
        UUID currentUserId = UserContext.getUserId();
        try (PreparedStatement statement = connection.prepareStatement(SET_CURRENT_USER)) {
            statement.setString(1, currentUserId == null ? "" : currentUserId.toString());
            statement.execute();
        } catch (SQLException e) {
            // Deliberately fatal. Previously this was swallowed, which was harmless while
            // the connection belonged to a superuser and policies never ran. Now a
            // connection that failed to be stamped is one that matches no rows: the
            // request would come back empty and succeed, and look like missing data
            // rather than a broken connection.
            connection.close();
            throw e;
        }
        return connection;
    }
}
