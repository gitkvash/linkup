package ge.kcamp.linkup.activity.place;

import ge.kcamp.linkup.activity.enums.PlaceKind;
import ge.kcamp.linkup.activity.place.OverpassResponse.Bounds;
import ge.kcamp.linkup.activity.place.OverpassResponse.Element;
import ge.kcamp.linkup.activity.place.OverpassResponse.Point;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OsmPlaceClassifierTest {

    @Test
    void aNotableParkKeepsItsEnglishAndGeorgianNamesAndGetsARadiusFromItsFootprint() {
        // Vake Park's real bounding box: about 650,000 m².
        Element vake = way(17233994, "relation", 41.7087, 44.7476,
                new Bounds(41.7050, 44.7420, 41.7125, 44.7530),
                Map.of("leisure", "park", "name", "ვაკის პარკი", "name:en", "Vake Park", "wikidata", "Q4003938"));

        OsmPlace place = OsmPlaceClassifier.classify(vake).orElseThrow();

        assertThat(place.osmRef()).isEqualTo("relation/17233994");
        assertThat(place.name()).isEqualTo("Vake Park");
        assertThat(place.nameKa()).isEqualTo("ვაკის პარკი");
        assertThat(place.kind()).isEqualTo(PlaceKind.PARK);
        assertThat(place.lat()).isEqualTo(41.7087);
        assertThat(place.radiusM()).isBetween(400, 550);
    }

    @Test
    void aWayWithOnlyABoxIsPlacedAtItsMiddle() {
        // What `out bb` returns for a way: bounds, no centre.
        Element lisi = new Element("way", 20841667, null, null, null,
                new Bounds(41.7400, 44.7300, 41.7480, 44.7390),
                Map.of("natural", "water", "water", "lake", "name", "Lisi Lake", "wikidata", "Q1"));

        OsmPlace place = OsmPlaceClassifier.classify(lisi).orElseThrow();

        assertThat(place.lat()).isCloseTo(41.7440, within(1e-9));
        assertThat(place.lng()).isCloseTo(44.7345, within(1e-9));
    }

    @Test
    void aSmallUnlinkedGardenIsNotAPlace() {
        Element courtyard = way(1, "way", 41.7, 44.8,
                new Bounds(41.7000, 44.8000, 41.7010, 44.8015),
                Map.of("leisure", "park", "name", "Courtyard Garden"));

        assertThat(OsmPlaceClassifier.classify(courtyard)).isEmpty();
    }

    @Test
    void aBigParkNeedsNoWikidata() {
        Element dendro = way(2, "relation", 41.75, 44.76,
                new Bounds(41.7400, 44.7500, 41.7600, 44.7700),
                Map.of("leisure", "park", "name", "Tbilisi Dendrological Park"));

        assertThat(OsmPlaceClassifier.classify(dendro)).isPresent();
    }

    @Test
    void aNodeGetsItsKindsTypicalRadiusAndNeedsWikidata() {
        Element linked = node(3, 41.69, 44.80,
                Map.of("tourism", "viewpoint", "name", "Viewpoint", "wikidata", "Q1"));
        Element unlinked = node(4, 41.69, 44.80,
                Map.of("tourism", "viewpoint", "name", "Some Bench"));

        assertThat(OsmPlaceClassifier.classify(linked).orElseThrow().radiusM()).isEqualTo(60);
        assertThat(OsmPlaceClassifier.classify(unlinked)).isEmpty();
    }

    @Test
    void aBrandLinkMakesAMallNotableButNotAGym() {
        Map<String, String> mall = Map.of("shop", "mall", "name", "City Mall", "brand:wikidata", "Q1");
        Map<String, String> gym = Map.of("leisure", "sports_centre", "name", "Chain Gym", "brand:wikidata", "Q2");

        assertThat(OsmPlaceClassifier.classify(node(5, 41.7, 44.7, mall))).isPresent();
        assertThat(OsmPlaceClassifier.classify(node(6, 41.7, 44.7, gym))).isEmpty();
    }

    @Test
    void aNotableBuildingIsALandmarkOnlyIfItIsBigOrACastle() {
        Map<String, String> house = Map.of("tourism", "attraction", "building", "house",
                "historic", "house", "name", "Kalantarov House", "wikidata", "Q1");
        Map<String, String> parliament = Map.of("tourism", "attraction", "building", "office",
                "name", "Parliament of Georgia", "wikidata", "Q2");
        Map<String, String> palace = Map.of("tourism", "attraction", "building", "yes",
                "historic", "castle", "name", "Queen Darejan's palace", "wikidata", "Q3");

        // ~1,000 m², ~21,000 m² and ~500 m² footprints.
        assertThat(OsmPlaceClassifier.classify(way(11, "way", 41.69, 44.80,
                new Bounds(41.6900, 44.8000, 41.6903, 44.8004), house))).isEmpty();
        assertThat(OsmPlaceClassifier.classify(way(12, "relation", 41.70, 44.78,
                new Bounds(41.7000, 44.7800, 41.7013, 44.7822), parliament))).isPresent();
        assertThat(OsmPlaceClassifier.classify(way(13, "way", 41.69, 44.81,
                new Bounds(41.6900, 44.8100, 41.6902, 44.8103), palace))).isPresent();
    }

    @Test
    void riversAndFountainsAreNotLakes() {
        assertThat(OsmPlaceClassifier.kindOf(Map.of("natural", "water", "water", "river"))).isNull();
        assertThat(OsmPlaceClassifier.kindOf(Map.of("natural", "water", "water", "fountain"))).isNull();
        assertThat(OsmPlaceClassifier.kindOf(Map.of("natural", "water", "water", "reservoir")))
                .isEqualTo(PlaceKind.LAKE);
        assertThat(OsmPlaceClassifier.kindOf(Map.of("natural", "water"))).isEqualTo(PlaceKind.LAKE);
    }

    @Test
    void theFirstMatchingKindWins() {
        assertThat(OsmPlaceClassifier.kindOf(Map.of("leisure", "park", "tourism", "attraction")))
                .isEqualTo(PlaceKind.PARK);
        assertThat(OsmPlaceClassifier.kindOf(Map.of("tourism", "zoo"))).isEqualTo(PlaceKind.PARK);
        assertThat(OsmPlaceClassifier.kindOf(Map.of("leisure", "stadium"))).isEqualTo(PlaceKind.SPORTS);
        assertThat(OsmPlaceClassifier.kindOf(Map.of("place", "square"))).isEqualTo(PlaceKind.LANDMARK);
        assertThat(OsmPlaceClassifier.kindOf(Map.of("amenity", "cafe"))).isNull();
    }

    @Test
    void unnamedOrPointlessFeaturesAreSkipped() {
        assertThat(OsmPlaceClassifier.classify(node(7, 41.7, 44.7,
                Map.of("leisure", "stadium", "wikidata", "Q1")))).isEmpty();
        assertThat(OsmPlaceClassifier.classify(new Element("way", 8, null, null, null, null,
                Map.of("leisure", "stadium", "name", "Nowhere", "wikidata", "Q1")))).isEmpty();
        assertThat(OsmPlaceClassifier.classify(new Element("node", 9, 41.7, 44.7, null, null, null)))
                .isEmpty();
    }

    @Test
    void radiiAreClampedToTheirBounds() {
        assertThat(OsmPlaceClassifier.radiusFor(PlaceKind.SPORTS, 100)).isEqualTo(OsmPlaceClassifier.MIN_RADIUS_M);
        assertThat(OsmPlaceClassifier.radiusFor(PlaceKind.LAKE, 42_000_000))
                .isEqualTo(OsmPlaceClassifier.MAX_RADIUS_M);
    }

    @Test
    void aLongNameIsCutToTheColumn() {
        String longName = "A".repeat(200);
        OsmPlace place = OsmPlaceClassifier.classify(node(10, 41.7, 44.7,
                Map.of("tourism", "attraction", "name", longName, "wikidata", "Q1"))).orElseThrow();

        assertThat(place.name()).hasSize(OsmPlaceClassifier.MAX_NAME_LENGTH);
        assertThat(place.nameKa()).isNull();
    }

    @Test
    void theSamePlaceMappedTwiceIsKeptOnceAsTheLargerOutline() {
        OsmPlace building = new OsmPlace("way/1", "East Point", null, PlaceKind.MALL, 41.6899, 44.8982, 280);
        OsmPlace shopNode = new OsmPlace("node/2", "East Point", null, PlaceKind.MALL, 41.6901, 44.8985, 80);
        OsmPlace elsewhere = new OsmPlace("way/3", "East Point", null, PlaceKind.MALL, 41.7500, 44.8000, 80);
        OsmPlace otherKind = new OsmPlace("way/4", "East Point", null, PlaceKind.SPORTS, 41.6900, 44.8983, 60);

        List<OsmPlace> kept = OsmPlaceClassifier.dedupe(List.of(shopNode, building, elsewhere, otherKind));

        assertThat(kept).extracting(OsmPlace::osmRef).containsExactlyInAnyOrder("way/1", "way/3", "way/4");
    }

    @Test
    void anOverpassAnswerParsesIncludingItsRemark() {
        String body = """
                {
                  "version": 0.6,
                  "osm3s": {"copyright": "ODbL"},
                  "elements": [
                    {"type": "node", "id": 1, "lat": 41.7, "lon": 44.8,
                     "tags": {"tourism": "viewpoint", "name": "View", "wikidata": "Q1"}},
                    {"type": "way", "id": 2, "center": {"lat": 41.74, "lon": 44.73},
                     "bounds": {"minlat": 41.74, "minlon": 44.72, "maxlat": 41.75, "maxlon": 44.74},
                     "tags": {"natural": "water", "water": "lake", "name": "Lisi Lake"}}
                  ],
                  "remark": "runtime error: Query timed out in \\"query\\" at line 3 after 120 seconds."
                }
                """;

        OverpassResponse response = JsonMapper.builder().build().readValue(body, OverpassResponse.class);

        assertThat(response.remark()).contains("error");
        assertThat(response.elements()).hasSize(2);
        assertThat(response.elements().get(1).center().lat()).isEqualTo(41.74);
        assertThat(response.elements().get(1).bounds().maxlon()).isEqualTo(44.74);
        assertThat(response.elements().stream().map(OsmPlaceClassifier::classify))
                .allMatch(java.util.Optional::isPresent);
    }

    private static Element way(long id, String type, double lat, double lng, Bounds bounds, Map<String, String> tags) {
        return new Element(type, id, null, null, new Point(lat, lng), bounds, tags);
    }

    private static Element node(long id, double lat, double lng, Map<String, String> tags) {
        return new Element("node", id, lat, lng, null, null, tags);
    }
}
