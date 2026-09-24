package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.query.ActivityQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin public wrapper over the internal {@link ActivityQueryRepository} - lets
 * {@code activity.query} stay internal while still serving other modules (e.g. the
 * feed module's influencer fan-out-on-read path).
 * <p>
 * Every method takes {@code viewerId}: reads are filtered to what that user may see.
 */
@Service
@Transactional(readOnly = true)
public class ActivityQueryService {

    private final ActivityQueryRepository activityQueryRepository;

    public ActivityQueryService(ActivityQueryRepository activityQueryRepository) {
        this.activityQueryRepository = activityQueryRepository;
    }

    /** Empty when the activity doesn't exist <em>or</em> the viewer may not see it. */
    public Optional<ActivityFeedItem> findById(UUID activityId, UUID viewerId) {
        return activityQueryRepository.findById(activityId, viewerId);
    }

    public List<ActivityFeedItem> findByIds(List<UUID> activityIds, UUID viewerId) {
        return activityQueryRepository.findByIds(activityIds, viewerId);
    }

    public List<ActivityFeedItem> findByCreator(UUID creatorId) {
        return activityQueryRepository.findByCreator(creatorId);
    }

    public List<ActivityFeedItem> findByCreatorIn(List<UUID> creatorIds, Instant after, UUID viewerId) {
        return activityQueryRepository.findByCreatorIn(creatorIds, after, viewerId);
    }

    /** As above, bounded above by start time; null {@code notAfter} means no bound. */
    public List<ActivityFeedItem> findByCreatorIn(
            List<UUID> creatorIds, Instant after, Instant notAfter, UUID viewerId) {
        return activityQueryRepository.findByCreatorIn(creatorIds, after, notAfter, viewerId);
    }
}
