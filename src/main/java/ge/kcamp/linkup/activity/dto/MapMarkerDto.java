package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.ActivityType;

import java.util.UUID;

/**
 * A single marker for the discovery map. {@code activityId/title/activityType} are only
 * populated when {@code type == PIN} (a single, unclustered activity).
 */
public record MapMarkerDto(
        MarkerType type,
        double lat,
        double lng,
        int count,
        UUID activityId,
        String title,
        ActivityType activityType
) {
    public enum MarkerType {
        PIN,
        CLUSTER
    }
}
