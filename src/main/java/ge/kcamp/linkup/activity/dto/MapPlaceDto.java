package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.PlaceKind;

import java.util.UUID;

/**
 * A well-known place on the discovery map: a lake, park, mall, arena or landmark, drawn
 * with its name so the map reads like a map of the city rather than a scatter of pins.
 * <p>
 * {@code plansThisWeek} counts the plans linked to the place (see V31's trigger) that the
 * caller may see, that aren't over, and that start within the next seven days. It is a
 * count of what the caller could open anyway, so it leaks nothing a pin wouldn't; counting
 * every plan would have said a private plan exists at the lake to people who can't see it.
 */
public record MapPlaceDto(
        UUID placeId,
        String name,
        String nameKa,
        PlaceKind kind,
        double lat,
        double lng,
        int plansThisWeek
) {
}
