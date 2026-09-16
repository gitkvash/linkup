import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Proves that row-level security is actually enforced, at the database, without the
 * application in the picture.
 *
 * <p>Everything the Java layer does to authorize a read is belt; this is braces. The two
 * are deliberately redundant, and the only way to know the braces are real is to connect
 * as the application's own role, claim to be somebody, and check what comes back.
 *
 * <p>Run it against a database that has been migrated to V17:
 *
 * <pre>
 *   java --class-path ~/.m2/repository/org/postgresql/postgresql/42.7.10/postgresql-42.7.10.jar \
 *        VerifyRls.java
 * </pre>
 *
 * <p>Overridable with DBURL / DBUSER / DBPASS (owner, used only to seed and clean up) and
 * APPUSER / APPPASS (the restricted role under test).
 */
public class VerifyRls {

    static final String URL = env("DBURL", "jdbc:postgresql://localhost:5433/linkup");
    static final String OWNER_USER = env("DBUSER", "linkup");
    static final String OWNER_PASS = env("DBPASS", "password");
    static final String APP_USER = env("APPUSER", "linkup_app");
    static final String APP_PASS = env("APPPASS", "app_password");

    static int passed = 0;
    static int failed = 0;

    // Seeded as the owner, then read back as the restricted role.
    static UUID alice;
    static UUID bob;      // alice's accepted friend
    static UUID mallory;  // no relationship to anyone
    static UUID friendsActivity;
    static UUID publicActivity;
    static UUID groupActivity;
    static UUID group;
    static String stamp;

    public static void main(String[] args) throws Exception {
        try (Connection owner = connect(OWNER_USER, OWNER_PASS)) {
            seed(owner);
            try {
                run();
            } finally {
                cleanUp(owner);
            }
        }

        System.out.println();
        System.out.printf("==== %d passed, %d failed ====%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void run() throws Exception {
        section("the role itself");
        try (Connection app = connect(APP_USER, APP_PASS)) {
            check("is not a superuser and cannot bypass RLS",
                    !bool(app, "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user"));
            check("does not own the tables it reads",
                    !bool(app, "SELECT pg_get_userbyid(relowner) = current_user "
                            + "FROM pg_class WHERE relname = 'activities'"));
        }

        section("a stranger sees nothing of alice's");
        try (Connection app = connectAs(mallory)) {
            check("cannot see the friends-only activity",
                    0 == count(app, "SELECT count(*) FROM activities WHERE activity_id = ?", friendsActivity));
            check("cannot see its location - the row holding the coordinates",
                    0 == count(app, "SELECT count(*) FROM locations WHERE activity_id = ?", friendsActivity));
            check("cannot see who is going",
                    0 == count(app, "SELECT count(*) FROM participants WHERE activity_id = ?", friendsActivity));
            check("cannot see alice's friendships",
                    0 == count(app, "SELECT count(*) FROM friendships WHERE user_a_id = ? OR user_b_id = ?",
                            alice, alice));
            check("cannot see alice's notifications",
                    0 == count(app, "SELECT count(*) FROM notifications WHERE recipient_user_id = ?", alice));
            check("cannot see alice's device tokens",
                    0 == count(app, "SELECT count(*) FROM device_tokens WHERE user_id = ?", alice));
            check("cannot see alice's group",
                    0 == count(app, "SELECT count(*) FROM groups WHERE group_id = ?", group));
            check("cannot see the group's members",
                    0 == count(app, "SELECT count(*) FROM group_members WHERE group_id = ?", group));
            check("cannot see the group-only activity",
                    0 == count(app, "SELECT count(*) FROM activities WHERE activity_id = ?", groupActivity));
            check("cannot see the group activity's location",
                    0 == count(app, "SELECT count(*) FROM locations WHERE activity_id = ?", groupActivity));
            check("CAN see the public activity",
                    1 == count(app, "SELECT count(*) FROM activities WHERE activity_id = ?", publicActivity));
        }

        section("an accepted friend sees what they should");
        try (Connection app = connectAs(bob)) {
            check("sees the friends-only activity",
                    1 == count(app, "SELECT count(*) FROM activities WHERE activity_id = ?", friendsActivity));
            check("sees its location",
                    1 == count(app, "SELECT count(*) FROM locations WHERE activity_id = ?", friendsActivity));
            check("sees the participant list",
                    1 == count(app, "SELECT count(*) FROM participants WHERE activity_id = ?", friendsActivity));
            check("sees the friendship row",
                    1 == count(app, "SELECT count(*) FROM friendships WHERE user_a_id = ? OR user_b_id = ?",
                            alice, alice));
            check("is a member, so sees the group",
                    1 == count(app, "SELECT count(*) FROM groups WHERE group_id = ?", group));
            // Reached by membership alone: bob is not a participant here, and GROUP
            // visibility does not consult friendship.
            check("sees the group-only activity",
                    1 == count(app, "SELECT count(*) FROM activities WHERE activity_id = ?", groupActivity));
            check("sees its location",
                    1 == count(app, "SELECT count(*) FROM locations WHERE activity_id = ?", groupActivity));
            check("still cannot read alice's notifications",
                    0 == count(app, "SELECT count(*) FROM notifications WHERE recipient_user_id = ?", alice));
        }

        section("writes are constrained too");
        try (Connection app = connectAs(mallory)) {
            check("cannot delete alice's account",
                    0 == update(app, "DELETE FROM users WHERE user_id = ?", alice));
            check("cannot retitle alice's activity",
                    0 == update(app, "UPDATE activities SET title = 'pwned' WHERE activity_id = ?", friendsActivity));
            check("cannot forge an activity in alice's name",
                    denied(app, "INSERT INTO activities (activity_id, creator_id, activity_type, title, "
                            + "visibility, start_time) VALUES (gen_random_uuid(), '" + alice + "', 'OTHER', "
                            + "'forged', 'PUBLIC', now())"));
            check("cannot add themselves to alice's group",
                    denied(app, "INSERT INTO group_members (group_id, user_id) VALUES ('"
                            + group + "', '" + mallory + "')"));
            check("cannot join a plan they cannot see",
                    // The activity is invisible, so app_activity_creator() finds nothing and the
                    // creator branch is NULL; the user_id branch is what would let them in, and
                    // the FK to an unreadable parent is not the thing stopping it - the point is
                    // only that the row does not become visible.
                    0 == count(app, "SELECT count(*) FROM activities WHERE activity_id = ?", friendsActivity));
        }

        section("the empty-string GUC regression (V15)");
        // RESET leaves a custom GUC as '' rather than NULL. Before app_current_user() was
        // NULLIF-guarded this threw "invalid input syntax for type uuid" on every policy
        // evaluation, which would have taken out every anonymous request served by a
        // recycled connection.
        try (Connection app = connect(APP_USER, APP_PASS)) {
            setCurrentUser(app, "");
            check("an unstamped connection does not error",
                    0 == count(app, "SELECT count(*) FROM activities WHERE activity_id = ?", friendsActivity));
            check("...and still sees public rows",
                    1 == count(app, "SELECT count(*) FROM activities WHERE activity_id = ?", publicActivity));
            check("login can still find an account by username",
                    1 == count(app, "SELECT count(*) FROM users WHERE user_id = ?", alice));
        }

        section("privileges the role must not have");
        try (Connection app = connectAs(mallory)) {
            check("cannot create tables in public",
                    denied(app, "CREATE TABLE rls_probe_should_fail (x int)"));
            check("cannot truncate a table",
                    denied(app, "TRUNCATE participants"));
            check("cannot read the migration history",
                    denied(app, "SELECT count(*) FROM flyway_schema_history"));
            check("cannot read password hashes out of pg_authid",
                    denied(app, "SELECT count(*) FROM pg_authid"));
        }

        section("the owner is still exempt - background work depends on it");
        try (Connection owner = connect(OWNER_USER, OWNER_PASS)) {
            setCurrentUser(owner, "");
            check("sees every activity with no user context",
                    1 == count(owner, "SELECT count(*) FROM activities WHERE activity_id = ?", friendsActivity));
            check("can write a notification for someone who is not the caller",
                    1 == update(owner, "INSERT INTO notifications (notification_id, recipient_user_id, type, title) "
                            + "VALUES (gen_random_uuid(), ?, 'PROBE', 'probe')", bob));
        }
    }

    // ---------------------------------------------------------------- seeding

    static void seed(Connection owner) throws SQLException {
        stamp = Long.toString(System.nanoTime());
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        mallory = UUID.randomUUID();
        friendsActivity = UUID.randomUUID();
        publicActivity = UUID.randomUUID();
        groupActivity = UUID.randomUUID();
        group = UUID.randomUUID();

        exec(owner, "INSERT INTO users (user_id, username, password_hash) VALUES (?, ?, 'x')",
                alice, "rls_alice_" + stamp);
        exec(owner, "INSERT INTO users (user_id, username, password_hash) VALUES (?, ?, 'x')",
                bob, "rls_bob_" + stamp);
        exec(owner, "INSERT INTO users (user_id, username, password_hash) VALUES (?, ?, 'x')",
                mallory, "rls_mallory_" + stamp);

        // Canonical ordering: V12 enforces unsigned user_a_id < user_b_id.
        UUID low = compareUnsigned(alice, bob) < 0 ? alice : bob;
        UUID high = low == alice ? bob : alice;
        exec(owner, "INSERT INTO friendships (user_a_id, user_b_id, status, requested_by) "
                + "VALUES (?, ?, 'ACCEPTED', ?)", low, high, alice);

        exec(owner, "INSERT INTO activities (activity_id, creator_id, activity_type, title, visibility, start_time) "
                + "VALUES (?, ?, 'OTHER', 'friends only', 'FRIENDS', now() + interval '1 day')",
                friendsActivity, alice);
        exec(owner, "INSERT INTO activities (activity_id, creator_id, activity_type, title, visibility, start_time) "
                + "VALUES (?, ?, 'OTHER', 'open to all', 'PUBLIC', now() + interval '1 day')",
                publicActivity, alice);
        exec(owner, "INSERT INTO locations (location_id, activity_id, geom_point, address_text) "
                + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(44.8, 41.7), 4326), 'somewhere private')",
                UUID.randomUUID(), friendsActivity);
        exec(owner, "INSERT INTO participants (activity_id, user_id, status) VALUES (?, ?, 'JOINED')",
                friendsActivity, alice);

        exec(owner, "INSERT INTO notifications (notification_id, recipient_user_id, type, title) "
                + "VALUES (?, ?, 'PROBE', 'private')", UUID.randomUUID(), alice);
        exec(owner, "INSERT INTO device_tokens (user_id, fcm_token, platform) VALUES (?, ?, 'ANDROID')",
                alice, "rls-probe-" + stamp);

        exec(owner, "INSERT INTO groups (group_id, owner_id, group_name) VALUES (?, ?, ?)",
                group, alice, "rls probe " + stamp);
        exec(owner, "INSERT INTO group_members (group_id, user_id) VALUES (?, ?)", group, alice);
        exec(owner, "INSERT INTO group_members (group_id, user_id) VALUES (?, ?)", group, bob);

        // Created after the group, since chk_activities_group_visibility and the FK both
        // need it to exist. Bob reaches this one purely by membership - he is not a
        // participant and friendship is irrelevant to GROUP visibility.
        exec(owner, "INSERT INTO activities (activity_id, creator_id, activity_type, title, visibility, "
                + "group_id, start_time) VALUES (?, ?, 'OTHER', 'group only', 'GROUP', ?, "
                + "now() + interval '1 day')", groupActivity, alice, group);
        exec(owner, "INSERT INTO locations (location_id, activity_id, geom_point, address_text) "
                + "VALUES (?, ?, ST_SetSRID(ST_MakePoint(44.81, 41.71), 4326), 'group clubhouse')",
                UUID.randomUUID(), groupActivity);
    }

    static void cleanUp(Connection owner) {
        // Order is load-bearing: activities now carry a group_id, so they have to go
        // before the groups they point at, and their own child rows before them.
        String[] statements = {
            "DELETE FROM notifications WHERE recipient_user_id IN (?, ?, ?)",
            "DELETE FROM device_tokens WHERE user_id IN (?, ?, ?)",
            "DELETE FROM participants WHERE activity_id IN "
                + "(SELECT activity_id FROM activities WHERE creator_id IN (?, ?, ?))",
            "DELETE FROM participants WHERE user_id IN (?, ?, ?)",
            "DELETE FROM locations WHERE activity_id IN "
                + "(SELECT activity_id FROM activities WHERE creator_id IN (?, ?, ?))",
            "DELETE FROM activities WHERE creator_id IN (?, ?, ?)",
            "DELETE FROM group_members WHERE user_id IN (?, ?, ?)",
            "DELETE FROM groups WHERE owner_id IN (?, ?, ?)",
            "DELETE FROM friendships WHERE user_a_id IN (?, ?, ?) OR user_b_id IN (?, ?, ?)",
            "DELETE FROM users WHERE user_id IN (?, ?, ?)",
        };
        for (String sql : statements) {
            try (PreparedStatement ps = owner.prepareStatement(sql)) {
                int params = ps.getParameterMetaData().getParameterCount();
                UUID[] ids = {alice, bob, mallory};
                for (int i = 0; i < params; i++) {
                    ps.setObject(i + 1, ids[i % 3]);
                }
                ps.execute();
            } catch (SQLException e) {
                System.out.println("  (cleanup) " + sql + " -> " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(URL, user, password);
    }

    /** A connection from the restricted role, stamped as {@code userId} the way RlsDataSource does. */
    static Connection connectAs(UUID userId) throws SQLException {
        Connection connection = connect(APP_USER, APP_PASS);
        setCurrentUser(connection, userId.toString());
        return connection;
    }

    static void setCurrentUser(Connection connection, String userId) throws SQLException {
        try (PreparedStatement ps =
                     connection.prepareStatement("SELECT set_config('app.current_user_id', ?, false)")) {
            ps.setString(1, userId);
            ps.execute();
        }
    }

    static void exec(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            ps.execute();
        }
    }

    static long count(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static boolean bool(Connection connection, String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    static int update(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** True when the statement is rejected outright, by a policy or by a missing privilege. */
    static boolean denied(Connection connection, String sql) {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
            return false;
        } catch (SQLException e) {
            return true;
        }
    }

    static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Postgres orders uuid unsigned; UUID.compareTo is signed. Matches SocialGraphService. */
    static int compareUnsigned(UUID x, UUID y) {
        int high = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
    }

    static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
        }
    }
}
