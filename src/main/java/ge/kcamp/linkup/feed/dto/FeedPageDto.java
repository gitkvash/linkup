package ge.kcamp.linkup.feed.dto;

import java.util.List;

public record FeedPageDto(
        List<FeedItemDto> items,
        Long nextCursor
) {
}
