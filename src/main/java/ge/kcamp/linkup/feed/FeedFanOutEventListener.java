package ge.kcamp.linkup.feed;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Independent listener on the same event {@code notification}'s ActivityEventListener
 * reacts to. {@code @ApplicationModuleListener} (rather than a plain
 * {@code @TransactionalEventListener}) gives at-least-once delivery via Modulith's event
 * publication log, which matters here since fan-out iterates over potentially many
 * friends.
 */
@Component
class FeedFanOutEventListener {

    private final FeedFanOutService feedFanOutService;

    FeedFanOutEventListener(FeedFanOutService feedFanOutService) {
        this.feedFanOutService = feedFanOutService;
    }

    @ApplicationModuleListener
    void onActivityCreated(ActivityCreatedEvent event) {
        // Ranked by when the activity happens, not when it was created: the feed is
        // ordered and paginated by start time, and scoring by creation instant meant
        // every cursor the client sent back pointed above the entire timeline.
        feedFanOutService.fanOutOnWrite(
                event.creatorId(), event.activityId(), event.startTime().toInstant(), event.groupId());
    }
}
