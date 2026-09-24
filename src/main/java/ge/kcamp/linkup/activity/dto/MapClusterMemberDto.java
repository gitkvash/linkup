package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityStatus;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One plan inside a map cluster: what the list opened from the cluster needs to show a
 * row, without a request per row.
 * <p>
 * Zooming in is how a cluster usually comes apart, but plans at the same place never do:
 * two plans in one cafe are one point at every zoom, and the cluster pill used to be all
 * the map could ever say about them. The members are what it lists instead.
 */
public record MapClusterMemberDto(
        UUID activityId,
        String title,
        ActivityCategory category,
        double lat,
        double lng,
        ZonedDateTime startTime,
        boolean hasTime,
        String addressText,
        ActivityStatus status
) {
}
