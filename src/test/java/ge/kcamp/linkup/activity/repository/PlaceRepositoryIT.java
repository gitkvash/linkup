package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.activity.dto.BoundingBox;
import ge.kcamp.linkup.activity.dto.MapClusterMemberDto;
import ge.kcamp.linkup.activity.dto.MapPlaceDto;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Location;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.PlaceKind;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded places (V31), the trigger that links a plan to the place it is at, and the
 * "plans this week" count and list the map reads.
 * <p>
 * Coordinates are the seed's own: Lisi Lake's centre is 41.743854, 44.734537.
 */
@SpringBootTest
class PlaceRepositoryIT extends AbstractIntegrationTest {

    private static final double LISI_LAT = 41.743854;
    private static final double LISI_LNG = 44.734537;

    /** Saburtalo and Lisi, and nothing east of Vake. */
    private static final BoundingBox WEST_TBILISI = new BoundingBox(41.70, 44.70, 41.76, 44.76);

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void theSeedIsOnTheMapWithItsKindAndGeorgianName() {
        UUID viewer = actAs(newUser());

        List<MapPlaceDto> places = placeRepository.findInBounds(WEST_TBILISI, viewer);

        MapPlaceDto lisi = byName(places, "Lisi Lake");
        assertThat(lisi.kind()).isEqualTo(PlaceKind.LAKE);
        assertThat(lisi.nameKa()).isEqualTo("ლისის ტბა");
        assertThat(lisi.plansThisWeek()).isZero();
        assertThat(places).extracting(MapPlaceDto::name)
                .contains("Arena 2", "City Mall Saburtalo")
                .doesNotContain("East Point", "Tbilisi Mall");
    }

    @Test
    void aPlanByTheLakeIsLinkedToItAndCountedThisWeek() {
        UUID creator = actAs(newUser());

        // ~200m north of the centre: well inside the lake's 550m.
        UUID activityId = createActivityAt(creator, "Swim at Lisi", LISI_LAT + 0.0018, LISI_LNG,
                ActivityVisibility.PUBLIC, ZonedDateTime.now().plusDays(2));

        assertThat(placeNameOf(activityId)).isEqualTo("Lisi Lake");
        assertThat(byName(placeRepository.findInBounds(WEST_TBILISI, creator), "Lisi Lake").plansThisWeek())
                .isEqualTo(1);
        assertThat(placeRepository.findPlansThisWeek(lisiId(), creator))
                .extracting(MapClusterMemberDto::title)
                .containsExactly("Swim at Lisi");
    }

    @Test
    void theSmallerPlaceWinsWhenTwoRadiiOverlap() {
        UUID creator = actAs(newUser());

        // Three fifths of the way from Mikheil Meskhi Stadium's centre to Vake Park's: ~96m
        // from the stadium (radius 150), ~64m from the park (radius 450). Nearest-centre
        // would say the park; the stadium is the more specific answer.
        UUID activityId = createActivityAt(creator, "Match", 41.709158, 44.747070,
                ActivityVisibility.PUBLIC, ZonedDateTime.now().plusDays(1));

        assertThat(placeNameOf(activityId)).isEqualTo("Mikheil Meskhi Stadium");
    }

    @Test
    void arenaTwoIsNotArenaOne() {
        UUID creator = actAs(newUser());

        // ~19m from Arena 2's centre, ~83m from Arena 1's (radius 60).
        UUID activityId = createActivityAt(creator, "Futsal", 41.718017, 44.741049,
                ActivityVisibility.PUBLIC, ZonedDateTime.now().plusDays(1));

        assertThat(placeNameOf(activityId)).isEqualTo("Arena 2");
    }

    @Test
    void movingAPlanAwayUnlinksIt() {
        UUID creator = actAs(newUser());

        UUID activityId = createActivityAt(creator, "Walk", LISI_LAT, LISI_LNG,
                ActivityVisibility.PUBLIC, ZonedDateTime.now().plusDays(1));
        assertThat(placeNameOf(activityId)).isEqualTo("Lisi Lake");

        Location location = locationRepository.findByActivityId(activityId).orElseThrow();
        location.setGeomPoint(geometryFactory.createPoint(new Coordinate(44.9, 41.65)));
        locationRepository.saveAndFlush(location);

        assertThat(placeNameOf(activityId)).isNull();
    }

    @Test
    void theCountOnlyIncludesPlansTheViewerCanSeeThisWeek() {
        UUID creator = actAs(newUser());

        createActivityAt(creator, "Public, Saturday", LISI_LAT, LISI_LNG,
                ActivityVisibility.PUBLIC, ZonedDateTime.now().plusDays(3));
        createActivityAt(creator, "Private, Saturday", LISI_LAT, LISI_LNG,
                ActivityVisibility.PRIVATE, ZonedDateTime.now().plusDays(3));
        createActivityAt(creator, "Public, next month", LISI_LAT, LISI_LNG,
                ActivityVisibility.PUBLIC, ZonedDateTime.now().plusDays(30));

        // The creator sees their private plan too; next month is outside the window.
        assertThat(byName(placeRepository.findInBounds(WEST_TBILISI, creator), "Lisi Lake").plansThisWeek())
                .isEqualTo(2);

        // A stranger sees the one public plan this week; the private one isn't hinted at.
        UUID stranger = actAs(newUser());
        assertThat(byName(placeRepository.findInBounds(WEST_TBILISI, stranger), "Lisi Lake").plansThisWeek())
                .isEqualTo(1);
        assertThat(placeRepository.findPlansThisWeek(lisiId(), stranger))
                .extracting(MapClusterMemberDto::title)
                .containsExactly("Public, Saturday");
    }

    private UUID lisiId() {
        return jdbcTemplate.queryForObject(
                "SELECT place_id FROM places WHERE name = 'Lisi Lake'", UUID.class);
    }

    /** Read through SQL: the link is the trigger's, and the entity doesn't map it. */
    private String placeNameOf(UUID activityId) {
        return jdbcTemplate.query("""
                        SELECT p.name FROM locations l
                        LEFT JOIN places p ON p.place_id = l.place_id
                        WHERE l.activity_id = ?
                        """,
                rs -> rs.next() ? rs.getString(1) : null,
                activityId);
    }

    private static MapPlaceDto byName(List<MapPlaceDto> places, String name) {
        return places.stream()
                .filter(place -> place.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + places));
    }

    private UUID createActivityAt(
            UUID creatorId, String title, double lat, double lng,
            ActivityVisibility visibility, ZonedDateTime startTime) {
        Activity saved = activityRepository.save(Activity.builder()
                .creatorId(creatorId)
                .activityType(ActivityType.SPECIFIC_EVENT)
                .title(title)
                .visibility(visibility)
                .category(ActivityCategory.GENERAL)
                .startTime(startTime)
                .hasTime(true)
                .build());

        locationRepository.saveAndFlush(Location.builder()
                .activity(saved)
                .geomPoint(geometryFactory.createPoint(new Coordinate(lng, lat)))
                .build());
        return saved.getId();
    }
}
