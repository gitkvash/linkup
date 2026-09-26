package ge.kcamp.linkup.activity.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.activity.dto.BoundingBox;
import ge.kcamp.linkup.activity.dto.MapClusterMemberDto;
import ge.kcamp.linkup.activity.dto.MapPlaceDto;
import ge.kcamp.linkup.activity.repository.PlaceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Well-known places for the discovery map: the named lakes, parks, malls and arenas it
 * draws alongside the plans, and the plans at each one this week.
 * <p>
 * Not under {@code /activities/map}: a place is not an activity and is drawn whether or not
 * anything is on there. It lives in the activity module anyway because the one thing a place
 * knows about is plans.
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final PlaceRepository placeRepository;

    public PlaceController(PlaceRepository placeRepository) {
        this.placeRepository = placeRepository;
    }

    /**
     * Unclustered: named places, each wanting its label, not a count. {@code zoom} is
     * optional and thins a zoomed-out view to the larger places
     * ({@link PlaceRepository#minRadiusForZoom}). Without it, every place in the box is
     * returned, which is what builds that predate the parameter get.
     */
    @GetMapping
    public List<MapPlaceDto> getInBounds(
            @RequestParam double minLat,
            @RequestParam double minLng,
            @RequestParam double maxLat,
            @RequestParam double maxLng,
            @RequestParam(required = false) Integer zoom) {

        BoundingBox bbox = new BoundingBox(minLat, minLng, maxLat, maxLng);
        bbox.validate();

        return placeRepository.findInBounds(bbox, UserContext.getUserId(), zoom);
    }

    @GetMapping("/{placeId}/activities")
    public List<MapClusterMemberDto> getPlansThisWeek(@PathVariable UUID placeId) {
        return placeRepository.findPlansThisWeek(placeId, UserContext.getUserId());
    }
}
