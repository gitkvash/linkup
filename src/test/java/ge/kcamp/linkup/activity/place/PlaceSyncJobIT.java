package ge.kcamp.linkup.activity.place;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.DatabaseRole;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Location;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.place.OverpassResponse.Bounds;
import ge.kcamp.linkup.activity.place.OverpassResponse.Element;
import ge.kcamp.linkup.activity.place.OverpassResponse.Point;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.LocationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A whole sync against the real schema, with Overpass stubbed: what it writes, what it
 * leaves alone (curated rows, {@code hidden}), what it deletes, and that plans follow.
 * <p>
 * The job is built by hand on a synchronous executor that stamps {@code SYSTEM}, as
 * {@code applicationTaskExecutor} would. The context's own job is switched off by
 * {@link AbstractIntegrationTest}.
 */
@SpringBootTest
class PlaceSyncJobIT extends AbstractIntegrationTest {

    /** Well clear of the curated places: open ground north of Gldani. */
    private static final double PARK_LAT = 41.8300;
    private static final double PARK_LNG = 44.8300;

    private static final List<SyncArea> AREAS = List.of(new SyncArea(41.60, 44.60, 41.87, 45.05));

    @Autowired
    private PlaceSyncRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    private OverpassClient overpass;
    private PlaceSyncJob job;

    @BeforeEach
    void setUp() {
        overpass = mock(OverpassClient.class);
        job = new PlaceSyncJob(overpass, repository, new TransactionTemplate(transactionManager),
                DatabaseRole::runAsSystem, true, AREAS,
                Duration.ofDays(7), Duration.ofHours(1), Duration.ZERO, Clock.systemUTC());
    }

    @AfterEach
    void removeSyncedPlaces() {
        asSystem("DELETE FROM places WHERE source = 'OSM'");
        asSystem("UPDATE place_sync SET last_success_at = NULL WHERE id = 1");
    }

    @Test
    void aSyncAddsNewPlacesLinksExistingPlansAndLeavesCuratedRowsAlone() {
        UUID creator = actAs(newUser());
        UUID planId = createActivityAt(creator, "Picnic", PARK_LAT, PARK_LNG);
        assertThat(placeNameOf(planId)).isNull();

        when(overpass.fetch(anyList())).thenReturn(List.of(
                park(900_000_001L, "Test Park", PARK_LAT, PARK_LNG),
                // Lisi Lake's own OSM id, under OSM's name: the curated row must not change.
                lake(20841667L, "Lisi Lake (OSM name)", 41.7438, 44.7345),
                // A second outline of Lisi under a new id, inside its 550m: a duplicate.
                lake(900_000_002L, "Lisi", 41.7450, 44.7360)));

        runSync();

        assertThat(placeNameOf(planId)).isEqualTo("Test Park");
        assertThat(osmNames()).containsExactly("Test Park");
        assertThat(jdbc.queryForObject("SELECT name FROM places WHERE osm_ref = 'way/20841667'", String.class))
                .isEqualTo("Lisi Lake");
        assertThat(lastSuccess()).isPresent();
    }

    @Test
    void aSyncThatIsNotDueDoesNotCallOverpass() {
        when(overpass.fetch(anyList())).thenReturn(List.of(park(900_000_001L, "Test Park", PARK_LAT, PARK_LNG)));

        runSync();
        runSync();

        verify(overpass, times(1)).fetch(anyList());
    }

    @Test
    void aPlaceGoneFromOsmIsDeletedAndItsPlansUnlinked() {
        UUID creator = actAs(newUser());
        UUID planId = createActivityAt(creator, "Picnic", PARK_LAT, PARK_LNG);

        when(overpass.fetch(anyList())).thenReturn(List.of(park(900_000_001L, "Test Park", PARK_LAT, PARK_LNG)));
        runSync();
        assertThat(placeNameOf(planId)).isEqualTo("Test Park");

        when(overpass.fetch(anyList())).thenReturn(List.of(park(900_000_003L, "Other Park", 41.8500, 44.9500)));
        makeDue();
        runSync();

        assertThat(osmNames()).containsExactly("Other Park");
        assertThat(placeNameOf(planId)).isNull();
    }

    @Test
    void hidingAPlaceSurvivesTheNextSync() {
        when(overpass.fetch(anyList())).thenReturn(List.of(park(900_000_001L, "Test Park", PARK_LAT, PARK_LNG)));
        runSync();
        asSystem("UPDATE places SET hidden = true WHERE osm_ref = 'way/900000001'");

        makeDue();
        runSync();

        assertThat(jdbc.queryForObject(
                "SELECT hidden FROM places WHERE osm_ref = 'way/900000001'", Boolean.class)).isTrue();
    }

    @Test
    void aMuchSmallerAnswerDeletesNothing() {
        when(overpass.fetch(anyList())).thenReturn(List.of(
                park(900_000_001L, "Park One", 41.8300, 44.8300),
                park(900_000_003L, "Park Two", 41.8500, 44.9500),
                park(900_000_004L, "Park Three", 41.8600, 44.9000)));
        runSync();

        when(overpass.fetch(anyList())).thenReturn(List.of(park(900_000_001L, "Park One", 41.8300, 44.8300)));
        makeDue();
        runSync();

        assertThat(osmNames()).containsExactlyInAnyOrder("Park One", "Park Two", "Park Three");
    }

    @Test
    void aFailedFetchRecordsNoSuccess() {
        when(overpass.fetch(anyList())).thenThrow(new IllegalStateException("Overpass answered only in part"));

        runSync();

        assertThat(lastSuccess()).isEmpty();
    }

    private void runSync() {
        job.tick();
    }

    private void makeDue() {
        asSystem("UPDATE place_sync SET last_success_at = now() - interval '8 days' WHERE id = 1");
    }

    /** {@code place_sync} is the owner's alone; the request role can't read it. */
    private Optional<OffsetDateTime> lastSuccess() {
        AtomicReference<Optional<OffsetDateTime>> last = new AtomicReference<>();
        DatabaseRole.runAsSystem(() -> last.set(repository.lastSuccess()));
        return last.get();
    }

    private void asSystem(String sql) {
        DatabaseRole.runAsSystem(() -> jdbc.update(sql));
    }

    private List<String> osmNames() {
        return jdbc.queryForList("SELECT name FROM places WHERE source = 'OSM' ORDER BY name", String.class);
    }

    private String placeNameOf(UUID activityId) {
        return jdbc.query("""
                        SELECT p.name FROM locations l
                        LEFT JOIN places p ON p.place_id = l.place_id
                        WHERE l.activity_id = ?
                        """,
                rs -> rs.next() ? rs.getString(1) : null,
                activityId);
    }

    /** A 400m-square park, notable, so the classifier keeps it. */
    private static Element park(long id, String name, double lat, double lng) {
        return new Element("way", id, null, null, new Point(lat, lng),
                new Bounds(lat - 0.0018, lng - 0.0024, lat + 0.0018, lng + 0.0024),
                Map.of("leisure", "park", "name", name, "wikidata", "Q" + id));
    }

    private static Element lake(long id, String name, double lat, double lng) {
        return new Element("way", id, null, null, new Point(lat, lng),
                new Bounds(lat - 0.004, lng - 0.005, lat + 0.004, lng + 0.005),
                Map.of("natural", "water", "water", "lake", "name", name, "wikidata", "Q" + id));
    }

    private UUID createActivityAt(UUID creatorId, String title, double lat, double lng) {
        Activity saved = activityRepository.save(Activity.builder()
                .creatorId(creatorId)
                .activityType(ActivityType.SPECIFIC_EVENT)
                .title(title)
                .visibility(ActivityVisibility.PUBLIC)
                .category(ActivityCategory.GENERAL)
                .startTime(ZonedDateTime.now().plusDays(1))
                .hasTime(true)
                .build());

        locationRepository.saveAndFlush(Location.builder()
                .activity(saved)
                .geomPoint(geometryFactory.createPoint(new Coordinate(lng, lat)))
                .build());
        return saved.getId();
    }
}
