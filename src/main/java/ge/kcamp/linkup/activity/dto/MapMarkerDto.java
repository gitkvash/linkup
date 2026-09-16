package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityType;

import java.util.UUID;

/**
 * A single marker for the discovery map. {@code activityId/title/activityType/category}
 * are only populated when {@code type == PIN} (a single, unclustered activity).
 * <p>
 * {@code category} is what the client draws inside the pin, so a glance at the map says
 * <em>what</em> is happening there and not only <em>that</em> something is. It is carried
 * here rather than fetched per pin because the alternative is one activity request per
 * dot on screen.
 */
public record MapMarkerDto(
        MarkerType type,
        double lat,
        double lng,
        int count,
        UUID activityId,
        String title,
        ActivityType activityType,
        ActivityCategory category
) {
    public enum MarkerType {
        PIN,
        CLUSTER
    }
}
