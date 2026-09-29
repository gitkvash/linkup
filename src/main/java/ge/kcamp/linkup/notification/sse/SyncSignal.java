package ge.kcamp.linkup.notification.sse;

import java.util.List;

/**
 * A silent "re-read this" hint, sent as the SSE event {@code sync}. It is not a
 * notification: nothing is stored, no push goes out, and it has no title or body - only
 * the names of what changed, so an open client can refetch the right lists at once.
 *
 * @param topics     any of {@code feed}, {@code map}, {@code plans}, {@code friends},
 *                   {@code requests}, {@code groups}
 * @param activityId the plan a {@code plans} signal is about, otherwise null
 * @param groupId    the group a {@code groups} signal is about, otherwise null
 */
public record SyncSignal(List<String> topics, String activityId, String groupId) {

    public SyncSignal(List<String> topics, String activityId) {
        this(topics, activityId, null);
    }

    public static final String FEED = "feed";
    public static final String MAP = "map";
    public static final String PLANS = "plans";
    public static final String FRIENDS = "friends";
    public static final String REQUESTS = "requests";
    public static final String GROUPS = "groups";
}
