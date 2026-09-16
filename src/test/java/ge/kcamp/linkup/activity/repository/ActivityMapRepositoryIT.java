package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.activity.dto.BoundingBox;
import ge.kcamp.linkup.activity.dto.MapMarkerDto;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Location;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the noise-point grouping fix: ST_ClusterDBSCAN returns NULL for points it
 * doesn't cluster, and a naive GROUP BY would collapse every noise point in the bbox
 * into one giant "cluster" instead of individual pins.
 */
@SpringBootTest
@Transactional
class ActivityMapRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ActivityMapRepository activityMapRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void groupsNearbyActivitiesIntoOneClusterAndLeavesFarActivityAsAPin() {
        UUID creatorId = UUID.randomUUID();
        // activity_insert_policy (RLS) requires creator_id == current_user_id.
        UserContext.setUserId(creatorId);

        // Two activities ~10m apart (should cluster together at a large eps).
        createActivityAt(creatorId, "Coffee A", 41.7151, 44.8271);
        createActivityAt(creatorId, "Coffee B", 41.71511, 44.82711);

        // One activity ~50km away (should remain its own pin / noise point).
        createActivityAt(creatorId, "Faraway Hike", 42.1, 45.3);

        BoundingBox bbox = new BoundingBox(40.0, 43.0, 43.0, 47.0);
        List<MapMarkerDto> markers = activityMapRepository.findClusteredMarkers(
                bbox, 100.0, 2, Instant.now().minus(1, ChronoUnit.HOURS), creatorId);

        long clusterCount = markers.stream().filter(m -> m.type() == MapMarkerDto.MarkerType.CLUSTER).count();
        long pinCount = markers.stream().filter(m -> m.type() == MapMarkerDto.MarkerType.PIN).count();

        assertThat(clusterCount).isEqualTo(1);
        assertThat(pinCount).isEqualTo(1);
        assertThat(markers.stream().filter(m -> m.type() == MapMarkerDto.MarkerType.CLUSTER).findFirst().get().count())
                .isEqualTo(2);
    }

    private void createActivityAt(UUID creatorId, String title, double lat, double lng) {
        Activity activity = Activity.builder()
                .creatorId(creatorId)
                .activityType(ActivityType.SPECIFIC_EVENT)
                .title(title)
                .visibility(ActivityVisibility.PUBLIC)
                .startTime(ZonedDateTime.now())
                .build();
        Activity saved = activityRepository.save(activity);

        Point point = geometryFactory.createPoint(new Coordinate(lng, lat));
        Location location = Location.builder().activity(saved).geomPoint(point).build();
        locationRepository.save(location);
    }
}
