package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ActivityType;

import java.util.List;
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
        ActivityCategory category,

        /*
         * Never ENDED: the map filters those out before clustering. A cluster reports LIVE
         * when any plan in it is, which is what makes it worth zooming into.
         */
        ActivityStatus status,

        /*
         * CLUSTER only: the plans in it, soonest first, at most
         * ActivityMapRepository.MAX_CLUSTER_MEMBERS of them - so fewer than count when the
         * cluster is bigger than that. Null on a PIN, which is its own single member.
         */
        List<MapClusterMemberDto> members
) {
    public enum MarkerType {
        PIN,
        CLUSTER
    }
}
