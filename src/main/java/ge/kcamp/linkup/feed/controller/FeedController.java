package ge.kcamp.linkup.feed.controller;

import ge.kcamp.linkup.feed.FeedQueryService;
import ge.kcamp.linkup.feed.dto.FeedPageDto;
import ge.kcamp.linkup.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedController {

    private final FeedQueryService feedQueryService;

    public FeedController(FeedQueryService feedQueryService) {
        this.feedQueryService = feedQueryService;
    }

    @GetMapping("/api/v1/feed")
    public FeedPageDto getFeed(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return feedQueryService.getFeed(UserContext.getUserId(), cursor, limit);
    }
}
