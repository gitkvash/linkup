package ge.kcamp.linkup.activity.place;

import ge.kcamp.linkup.activity.enums.PlaceKind;
import ge.kcamp.linkup.activity.place.PlaceSyncRepository.CuratedPlace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parts of the sync that need no database or network. */
class PlaceSyncUnitTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 26, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Duration WEEK = Duration.ofDays(7);

    @Test
    void aSyncIsDueWhenNoneHasFinishedOrTheLastIsAWeekOld() {
        assertThat(PlaceSyncJob.isDue(Optional.empty(), NOW, WEEK)).isTrue();
        assertThat(PlaceSyncJob.isDue(Optional.of(NOW.minusDays(7)), NOW, WEEK)).isTrue();
        assertThat(PlaceSyncJob.isDue(Optional.of(NOW.minusDays(6)), NOW, WEEK)).isFalse();
    }

    @Test
    void aCandidateThatIsACuratedPlaceIsDropped() {
        CuratedPlace lisi = new CuratedPlace("way/20841667", PlaceKind.LAKE, 41.743854, 44.734537, 550);
        OsmPlace sameFeature = new OsmPlace("way/20841667", "Lisi Lake", null, PlaceKind.LAKE, 41.744, 44.734, 450);
        OsmPlace remapped = new OsmPlace("relation/1", "Lisi", null, PlaceKind.LAKE, 41.745, 44.736, 450);
        OsmPlace otherKindInside = new OsmPlace("way/2", "Lisi Hippodrome", null, PlaceKind.SPORTS, 41.745, 44.736, 290);
        OsmPlace sameKindOutside = new OsmPlace("way/3", "Turtle Lake", null, PlaceKind.LAKE, 41.700, 44.754, 150);

        List<OsmPlace> kept = PlaceSyncJob.withoutCurated(
                List.of(sameFeature, remapped, otherKindInside, sameKindOutside), List.of(lisi));

        assertThat(kept).extracting(OsmPlace::osmRef).containsExactly("way/2", "way/3");
    }

    @Test
    void areasParseAndRenderInOverpassOrder() {
        List<SyncArea> areas = SyncArea.parseAll(" 41.60,44.60,41.87,45.05 ; 41.56,41.55,41.70,41.72; ");

        assertThat(areas).containsExactly(
                new SyncArea(41.60, 44.60, 41.87, 45.05),
                new SyncArea(41.56, 41.55, 41.70, 41.72));
        assertThat(areas.getFirst().overpassBbox()).isEqualTo("41.60000,44.60000,41.87000,45.05000");
        assertThat(SyncArea.parseAll("")).isEmpty();
    }

    @Test
    void aMalformedAreaFailsLoudly() {
        assertThatThrownBy(() -> SyncArea.parseAll("41.6,44.6,41.8")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyncArea.parseAll("41.9,44.6,41.8,45.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SyncArea.parseAll("north,44.6,41.8,45.0")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theQueryAsksForEveryKindInEveryAreaWithoutGeometry() {
        String query = OverpassClient.query(List.of(
                new SyncArea(41.60, 44.60, 41.87, 45.05),
                new SyncArea(42.20, 42.60, 42.32, 42.80)));

        assertThat(query).startsWith("[out:json][timeout:60];");
        assertThat(query).contains("nwr[\"shop\"=\"mall\"][\"name\"](41.60000,44.60000,41.87000,45.05000);");
        assertThat(query).contains("nwr[\"shop\"=\"mall\"][\"name\"](42.20000,42.60000,42.32000,42.80000);");
        assertThat(query).endsWith("out bb tags;\n");
    }
}
