package ge.kcamp.linkup.feed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure cache, not a durable log: a Redis flush loses timelines, which is an accepted
 * tradeoff of the fan-out-on-write strategy (activities themselves remain in Postgres).
 */
@Component
class RedisFeedTimelineRepository {

    /**
     * Bumped from the unversioned {@code feed:timeline:} prefix when the score changed
     * meaning (see {@link FeedTimelineScore}). Old keys expire on their existing TTL, so
     * no flush or deploy ordering is needed - but the two score schemes must never share
     * a sorted set, which is exactly what a prefix bump guarantees.
     */
    private static final String KEY_PREFIX = "feed:timeline:v2:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlDays;
    private final long maxTimelineSize;

    RedisFeedTimelineRepository(
            StringRedisTemplate redisTemplate,
            @Value("${linkup.feed.ttl-days}") long ttlDays,
            @Value("${linkup.feed.max-timeline-size}") long maxTimelineSize) {
        this.redisTemplate = redisTemplate;
        this.ttlDays = ttlDays;
        this.maxTimelineSize = maxTimelineSize;
    }

    void push(UUID userId, UUID activityId, double score) {
        pushAll(List.of(userId), activityId, score);
    }

    /**
     * Fan-out to many timelines in one round trip. Each timeline needs three commands
     * (add, trim, refresh TTL), so writing to a user with 200 friends was 600 sequential
     * round trips.
     */
    void pushAll(Collection<UUID> userIds, UUID activityId, double score) {
        if (userIds.isEmpty()) {
            return;
        }
        String member = activityId.toString();
        Duration ttl = Duration.ofDays(ttlDays);

        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                for (UUID userId : userIds) {
                    String key = timelineKey(userId);
                    ops.opsForZSet().add(key, member, score);
                    ops.opsForZSet().removeRange(key, 0, -(maxTimelineSize + 1));
                    ops.expire(key, ttl);
                }
                // Pipelined callbacks must return null; results are collected by the template.
                return null;
            }
        });
    }

    /**
     * Entries strictly below {@code beforeScore}, highest first. Pass null for the first
     * page. Exclusivity is safe because scores are near-unique per activity - with the
     * old start-time-only score, subtracting 1 silently skipped every activity sharing a
     * millisecond with the previous page's last item, and a time picker produces those
     * collisions constantly.
     */
    List<UUID> read(UUID userId, Double beforeScore, int limit) {
        double max = beforeScore == null ? Double.POSITIVE_INFINITY : beforeScore - 1;
        Set<String> ids = redisTemplate.opsForZSet()
                .reverseRangeByScore(timelineKey(userId), Double.NEGATIVE_INFINITY, max, 0, limit);
        if (ids == null) {
            return List.of();
        }
        return ids.stream().map(UUID::fromString).toList();
    }

    /** One timeline entry and the score it is stored under. */
    record Entry(UUID activityId, double score) {
    }

    /**
     * As {@link #read}, with each entry's score. The feed pages on the stored score, so
     * it needs the score of an entry it then filters out, not only of the ones it keeps.
     */
    List<Entry> readScored(UUID userId, Double beforeScore, int limit) {
        double max = beforeScore == null ? Double.POSITIVE_INFINITY : beforeScore - 1;
        Set<TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(timelineKey(userId), Double.NEGATIVE_INFINITY, max, 0, limit);
        if (tuples == null) {
            return List.of();
        }
        return tuples.stream()
                .map(tuple -> new Entry(UUID.fromString(tuple.getValue()), tuple.getScore()))
                .toList();
    }

    /** Drops entries the feed found no row for, or no longer may show this user. */
    void remove(UUID userId, Collection<UUID> activityIds) {
        if (activityIds.isEmpty()) {
            return;
        }
        redisTemplate.opsForZSet().remove(timelineKey(userId),
                activityIds.stream().map(UUID::toString).toArray());
    }

    private static String timelineKey(UUID userId) {
        return KEY_PREFIX + userId;
    }
}
