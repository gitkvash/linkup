package ge.kcamp.linkup.activity;

/**
 * The one place that decides who may see an activity. Every read path against
 * {@code activities} composes {@link #VISIBLE_TO_VIEWER} into its WHERE clause and
 * binds {@code :viewerId}.
 * <p>
 * This used to be left entirely to the {@code activity_select_policy} row-level
 * security policy from {@code V4__rls_policies.sql}, on the strength of comments
 * saying so. Those comments were wrong: PostgreSQL exempts a table's owner from RLS
 * unless the table is marked {@code FORCE ROW LEVEL SECURITY}, and the application
 * connects as the owning role - so the policies never filtered anything, and
 * {@code GET /activities/{id}} would hand any caller the title, address and exact
 * coordinates of anyone's private plan.
 * <p>
 * RLS is enforced as well now - V17 moved request handling onto {@code linkup_app},
 * which owns nothing and cannot bypass policies - but this predicate is still the
 * primary boundary. It has to exist independently for the map query, where clustering
 * happens before any row filter could usefully apply, and because RLS cannot express
 * "answer 404 rather than 403".
 * <p>
 * Keep it in step with {@code app_can_see_activity()} in the database (V15, extended by
 * V18). The two deliberately say the same thing; if they drift, the stricter one wins
 * and rows vanish with nothing to explain why.
 */
public final class ActivityVisibilitySql {

    private ActivityVisibilitySql() {
    }

    /** Bind parameter every caller of {@link #VISIBLE_TO_VIEWER} must supply. */
    public static final String VIEWER_ID_PARAM = "viewerId";

    /**
     * Requires the {@code activities} table to be aliased {@code a}. Wrapped in
     * parentheses so it can be AND-ed into any WHERE clause safely.
     */
    public static final String VISIBLE_TO_VIEWER = """
            (
                a.creator_id = :viewerId
                OR a.visibility = 'PUBLIC'
                OR EXISTS (
                    SELECT 1 FROM participants vp
                    WHERE vp.activity_id = a.activity_id AND vp.user_id = :viewerId
                )
                OR (a.visibility = 'FRIENDS' AND EXISTS (
                    SELECT 1 FROM friendships f
                    WHERE f.status = 'ACCEPTED'
                      AND (
                          (f.user_a_id = a.creator_id AND f.user_b_id = :viewerId)
                          OR (f.user_b_id = a.creator_id AND f.user_a_id = :viewerId)
                      )
                ))
                OR (a.visibility = 'GROUP' AND EXISTS (
                    SELECT 1 FROM group_members gm
                    WHERE gm.group_id = a.group_id AND gm.user_id = :viewerId
                ))
            )
            """;
}
