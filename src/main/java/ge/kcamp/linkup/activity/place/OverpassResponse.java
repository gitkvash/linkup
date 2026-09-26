package ge.kcamp.linkup.activity.place;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The part of an Overpass {@code [out:json]} answer to {@code out bb tags} that the
 * sync reads.
 * <p>
 * {@code remark} matters more than it looks. When a query runs out of time or memory,
 * Overpass still answers 200, with whatever it had found so far and the error in the
 * remark. Taken at face value, that partial answer would delete every place it missed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OverpassResponse(List<Element> elements, String remark) {

    /**
     * A node carries its own {@code lat}/{@code lon}. A way or relation carries its
     * {@code bounds} instead, or a {@code center} if the query asked {@code out center}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Element(
            String type,
            long id,
            Double lat,
            Double lon,
            Point center,
            Bounds bounds,
            Map<String, String> tags) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Point(double lat, double lon) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Bounds(double minlat, double minlon, double maxlat, double maxlon) {
    }
}
