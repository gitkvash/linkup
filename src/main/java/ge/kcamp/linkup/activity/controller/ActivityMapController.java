package ge.kcamp.linkup.activity.controller;

import ge.kcamp.linkup.activity.dto.BoundingBox;
import ge.kcamp.linkup.activity.dto.MapMarkerDto;
import ge.kcamp.linkup.activity.repository.ActivityMapRepository;
import ge.kcamp.linkup.activity.web.ZoomClusterResolver;
import ge.kcamp.linkup.UserContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

// No @Validated: Spring 6.1+ applies built-in method validation to constrained
// controller parameters and raises HandlerMethodValidationException (a
// ResponseStatusException, so the global advice turns it into a 400 with a detail).
// Adding @Validated would switch it to the AOP path and ConstraintViolationException.
@RestController
@RequestMapping("/api/v1/activities/map")
public class ActivityMapController {

    private final ActivityMapRepository activityMapRepository;
    private final ZoomClusterResolver zoomClusterResolver;

    public ActivityMapController(ActivityMapRepository activityMapRepository, ZoomClusterResolver zoomClusterResolver) {
        this.activityMapRepository = activityMapRepository;
        this.zoomClusterResolver = zoomClusterResolver;
    }

    /**
     * The clustering parameters are derived from {@code zoom} only. The former
     * {@code epsOverride}/{@code minPointsOverride} query parameters were unvalidated
     * and fed straight into PostGIS (a caller could ask for {@code minPoints=0} or a
     * negative radius); no client ever sent them.
     */
    @GetMapping
    public List<MapMarkerDto> getMarkers(
            @RequestParam double minLat,
            @RequestParam double minLng,
            @RequestParam double maxLat,
            @RequestParam double maxLng,
            @RequestParam @Min(0) @Max(22) int zoom) {

        BoundingBox bbox = new BoundingBox(minLat, minLng, maxLat, maxLng);
        bbox.validate();

        ZoomClusterResolver.ClusterParams params = zoomClusterResolver.resolve(zoom);
        Instant notBefore = Instant.now().minus(1, ChronoUnit.HOURS);

        return activityMapRepository.findClusteredMarkers(
                bbox, params.epsMeters(), params.minPoints(), notBefore, UserContext.getUserId());
    }
}
