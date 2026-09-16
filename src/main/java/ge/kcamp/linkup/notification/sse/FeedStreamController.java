package ge.kcamp.linkup.notification.sse;

import ge.kcamp.linkup.UserContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class FeedStreamController {

    private final SseEmitterRegistry emitterRegistry;

    public FeedStreamController(SseEmitterRegistry emitterRegistry) {
        this.emitterRegistry = emitterRegistry;
    }

    @GetMapping(value = "/api/v1/feed/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return emitterRegistry.register(UserContext.getUserId());
    }
}
