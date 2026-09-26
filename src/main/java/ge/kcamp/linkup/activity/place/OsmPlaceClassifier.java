package ge.kcamp.linkup.activity.place;

import ge.kcamp.linkup.activity.enums.PlaceKind;
import ge.kcamp.linkup.activity.place.OverpassResponse.Bounds;
import ge.kcamp.linkup.activity.place.OverpassResponse.Element;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which OSM features are well-known places, and what kind each one is.
 * <p>
 * The Overpass query (see {@link OverpassClient#query}) is wide on purpose: every named
 * park, water body, mall, sports ground and attraction in the area. For Tbilisi that is
 * about 300 features, among them a 1,500 m² courtyard garden and a gym that is only a
 * node. The map wants the places people make plans at. Two signals pick them out, and a
 * feature needs one of them:
 * <ul>
 *   <li>a {@code wikidata} tag: someone thought it notable enough to link to an
 *       encyclopedia entry, which is close to what "well-known" means. For a mall, its
 *       chain's {@code brand:wikidata} counts too.</li>
 *   <li>a footprint over the kind's {@link #minAreaM2}, since a big park or lake is a
 *       landmark whether or not anyone has linked it</li>
 * </ul>
 * Footprints are estimated from the feature's bounding box, which is all
 * {@code out bb} returns. That overestimates anything diagonal or irregular. It
 * is cheap, though, and it only decides inclusion and a radius: two judgements the box is
 * good enough for.
 */
final class OsmPlaceClassifier {

    /** {@code places.name} and {@code name_ka} are varchar(120). */
    static final int MAX_NAME_LENGTH = 120;

    /**
     * Radius bounds for a place with a footprint. The floor matches the tightest curated
     * radii (Arena 1 and Arena 2, about 100 m apart). The ceiling keeps one huge feature
     * from claiming every plan in a district.
     */
    static final int MIN_RADIUS_M = 40;
    static final int MAX_RADIUS_M = 2500;

    /** Water that is a stretch of something rather than a place you go to. */
    private static final Set<String> NOT_A_LAKE = Set.of(
            "river", "canal", "stream", "ditch", "drain", "wastewater", "basin",
            "fountain", "moat", "reflecting_pool", "stream_pool");

    private static final Set<String> PARK_LEISURE = Set.of("park", "garden");
    private static final Set<String> PARK_TOURISM = Set.of("zoo", "theme_park");
    private static final Set<String> SPORTS_LEISURE = Set.of(
            "stadium", "sports_centre", "sports_hall", "ice_rink", "water_park");
    private static final Set<String> LANDMARK_TOURISM = Set.of("attraction", "viewpoint");
    private static final Set<String> LANDMARK_HISTORIC = Set.of("castle", "fort", "monastery");

    /**
     * The footprint a building needs to be a landmark, wikidata or not. In Tbilisi, half the
     * notable attractions are historic townhouses (Kalantarov House, Bozarjants House) and
     * offices: worth an encyclopedia entry, but nobody makes plans at one. Parliament and
     * the old Melik-Azaryants block are big enough to meet outside. A castle or monastery
     * counts at any size.
     */
    static final double MIN_LANDMARK_BUILDING_AREA_M2 = 10_000;

    /** Two features of one kind and name within this distance are the same place. */
    private static final double DUPLICATE_WITHIN_M = 300;

    private OsmPlaceClassifier() {
    }

    /** Empty for anything unnamed, of no kind the map draws, or not well-known. */
    static Optional<OsmPlace> classify(Element element) {
        Map<String, String> tags = element.tags();
        if (tags == null) {
            return Optional.empty();
        }
        String localName = blankToNull(tags.get("name"));
        if (localName == null) {
            return Optional.empty();
        }
        PlaceKind kind = kindOf(tags);
        if (kind == null) {
            return Optional.empty();
        }
        double[] point = pointOf(element);
        if (point == null) {
            return Optional.empty();
        }

        double area = bboxAreaM2(element.bounds());
        // A mall chain's brand link counts for its malls; a gym chain's doesn't make every
        // branch a landmark.
        boolean notable = tags.containsKey("wikidata")
                || (kind == PlaceKind.MALL && tags.containsKey("brand:wikidata"));
        if (!notable && area < minAreaM2(kind)) {
            return Optional.empty();
        }
        if (isJustABuilding(kind, tags) && area < MIN_LANDMARK_BUILDING_AREA_M2) {
            return Optional.empty();
        }

        String english = blankToNull(tags.get("name:en"));
        String georgian = blankToNull(tags.get("name:ka"));
        if (georgian == null && isGeorgian(localName)) {
            georgian = localName;
        }

        return Optional.of(new OsmPlace(
                element.type() + "/" + element.id(),
                truncate(english != null ? english : localName),
                georgian == null ? null : truncate(georgian),
                kind,
                point[0],
                point[1],
                radiusFor(kind, area)));
    }

    /**
     * First match wins, in this order: a park that is also tagged an attraction is a
     * park, and a zoo draws as a park because that is what a visit to one is like.
     */
    static PlaceKind kindOf(Map<String, String> tags) {
        String leisure = tags.get("leisure");
        String tourism = tags.get("tourism");

        if ("water".equals(tags.get("natural")) && !in(NOT_A_LAKE, tags.get("water"))) {
            return PlaceKind.LAKE;
        }
        if (in(PARK_LEISURE, leisure) || in(PARK_TOURISM, tourism)) {
            return PlaceKind.PARK;
        }
        if ("mall".equals(tags.get("shop"))) {
            return PlaceKind.MALL;
        }
        if (in(SPORTS_LEISURE, leisure)) {
            return PlaceKind.SPORTS;
        }
        if (in(LANDMARK_TOURISM, tourism)
                || "square".equals(tags.get("place"))
                || in(LANDMARK_HISTORIC, tags.get("historic"))) {
            return PlaceKind.LANDMARK;
        }
        return null;
    }

    /**
     * Below this footprint a feature needs a wikidata tag to count. Tuned against Tbilisi
     * in September 2026, where it keeps Vera Park (10 ha) and drops a 2 ha housing-block
     * garden. Malls and arenas are smaller than parks by nature, so their bar is lower.
     */
    static double minAreaM2(PlaceKind kind) {
        return switch (kind) {
            case LAKE -> 20_000;
            case PARK -> 30_000;
            case MALL, SPORTS -> 5_000;
            case LANDMARK -> 20_000;
        };
    }

    /**
     * The radius of a circle with the feature's footprint, so a plan anywhere on a lake
     * counts as being at it. A node has no footprint and gets its kind's typical size.
     */
    static int radiusFor(PlaceKind kind, double areaM2) {
        if (areaM2 <= 0) {
            return switch (kind) {
                case LAKE -> 150;
                case PARK -> 120;
                case MALL -> 80;
                case SPORTS, LANDMARK -> 60;
            };
        }
        long radius = Math.round(Math.sqrt(areaM2 / Math.PI));
        return (int) Math.clamp(radius, MIN_RADIUS_M, MAX_RADIUS_M);
    }

    /**
     * One place per real place. OSM often maps a mall twice (a node for the shop and a
     * way for the building), and a park split into two relations shares a name. The
     * larger one is kept, since it is the better outline of where people will be.
     */
    static List<OsmPlace> dedupe(List<OsmPlace> places) {
        List<OsmPlace> bySize = new ArrayList<>(places);
        bySize.sort(Comparator.comparingInt(OsmPlace::radiusM).reversed()
                .thenComparing(OsmPlace::osmRef));

        List<OsmPlace> kept = new ArrayList<>();
        for (OsmPlace candidate : bySize) {
            boolean duplicate = kept.stream().anyMatch(place ->
                    place.kind() == candidate.kind()
                            && normalise(place.name()).equals(normalise(candidate.name()))
                            && distanceM(place.lat(), place.lng(), candidate.lat(), candidate.lng())
                            <= Math.max(DUPLICATE_WITHIN_M, place.radiusM()));
            if (!duplicate) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /** Great-circle distance, which at city scale is as exact as the data. */
    static double distanceM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
    }

    private static boolean isJustABuilding(PlaceKind kind, Map<String, String> tags) {
        return kind == PlaceKind.LANDMARK
                && tags.containsKey("building")
                && !in(LANDMARK_HISTORIC, tags.get("historic"));
    }

    /** {@code Set.of(...).contains(null)} throws, and most tags are absent from most features. */
    private static boolean in(Set<String> values, String value) {
        return value != null && values.contains(value);
    }

    private static double[] pointOf(Element element) {
        if (element.lat() != null && element.lon() != null) {
            return new double[] {element.lat(), element.lon()};
        }
        if (element.center() != null) {
            return new double[] {element.center().lat(), element.center().lon()};
        }
        // What `out center` would have given: Overpass's centre is the middle of the box.
        Bounds bounds = element.bounds();
        if (bounds != null) {
            return new double[] {
                    (bounds.minlat() + bounds.maxlat()) / 2, (bounds.minlon() + bounds.maxlon()) / 2};
        }
        return null;
    }

    private static double bboxAreaM2(Bounds bounds) {
        if (bounds == null) {
            return 0;
        }
        double metresPerDegree = 111_320;
        double midLat = Math.toRadians((bounds.minlat() + bounds.maxlat()) / 2);
        double height = (bounds.maxlat() - bounds.minlat()) * metresPerDegree;
        double width = (bounds.maxlon() - bounds.minlon()) * metresPerDegree * Math.cos(midLat);
        return Math.max(0, height * width);
    }

    /** The Georgian (Mkhedruli) block. */
    private static boolean isGeorgian(String text) {
        return text.codePoints().anyMatch(c -> c >= 0x10A0 && c <= 0x10FF);
    }

    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String truncate(String name) {
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH).strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
