package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.ActivityCategory;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One activity matched by the map's search box.
 * <p>
 * Deliberately not a {@link MapMarkerDto}: a marker answers "where", and the search
 * result list has to answer "which one" — so it carries the time and the address the
 * marker endpoint has no room for, and the distance from where the user is looking,
 * which is what the list is ordered by. Only activities that can be placed on the map
 * are returned; a plan with no coordinates has nowhere to fly to.
 */
public record MapSearchResultDto(
        UUID activityId,
        String title,
        ActivityCategory category,
        double lat,
        double lng,
        ZonedDateTime startTime,
        boolean hasTime,
        String addressText,
        double distanceMeters
) {
}
