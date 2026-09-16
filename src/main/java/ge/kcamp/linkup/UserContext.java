package ge.kcamp.linkup;

import java.util.UUID;

/**
 * Holds the authenticated user's id for the duration of a request.
 *
 * <p>Lives in the application's root package - shared kernel, belonging to no module -
 * rather than in {@code identity}, where it started. Every module's controllers already
 * read it, and once {@code DataSourceConfig} needed it too (to stamp each connection for
 * row-level security) the root package depended on {@code identity} while
 * {@code identity} depended on the root, which {@code ApplicationModules.verify()}
 * correctly rejected as a cycle. It is written in exactly one place,
 * {@code JwtAuthenticationFilter}.
 */
public class UserContext {
    private static final ThreadLocal<UUID> currentUserId = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(UUID userId) {
        currentUserId.set(userId);
    }

    public static UUID getUserId() {
        return currentUserId.get();
    }

    public static void clear() {
        currentUserId.remove();
    }
}
