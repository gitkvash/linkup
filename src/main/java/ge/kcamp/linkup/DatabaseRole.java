package ge.kcamp.linkup;

/**
 * Which database login the current thread's connections are drawn from.
 *
 * <p>{@link #APP} is {@code linkup_app}: no ownership, no superuser bit, no BYPASSRLS, so
 * every row-level policy in the schema applies to it. It is the default, and it is the
 * default deliberately - a code path that forgets to declare itself gets the restricted
 * role and fails visibly, rather than quietly gaining the ability to read everyone's data.
 *
 * <p>{@link #SYSTEM} is the schema owner, which Postgres exempts from policies. It exists
 * for work that has no user to act on behalf of and therefore cannot be expressed as a
 * policy: the notification dispatcher writes rows for a recipient who is not the caller,
 * and feed fan-out reads a creator's friend list from a background thread. Under the app
 * role both would return zero rows and no error - a policy that matches nothing looks
 * exactly like data that does not exist.
 *
 * <p>Nothing calls {@link #runAsSystem} by hand. The marker is applied to whole threads by
 * the {@code TaskDecorator} on the async executor in {@code DataSourceConfig}, so the rule
 * is one sentence: <em>work that is not serving an HTTP request runs as the system role.</em>
 * Doing it at the executor rather than around each call also sidesteps a subtlety - by the
 * time a {@code @Transactional} method body runs, its connection may already have been
 * acquired, and switching the marker then would come too late.
 */
public enum DatabaseRole {

    APP,
    SYSTEM;

    private static final ThreadLocal<DatabaseRole> CURRENT = new ThreadLocal<>();

    /** Never null: an unmarked thread is an application thread. */
    public static DatabaseRole current() {
        DatabaseRole role = CURRENT.get();
        return role == null ? APP : role;
    }

    /**
     * Runs {@code body} with system-role connections. Restores the previous marker rather
     * than clearing it, so nesting behaves.
     */
    public static void runAsSystem(Runnable body) {
        DatabaseRole previous = CURRENT.get();
        CURRENT.set(SYSTEM);
        try {
            body.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
