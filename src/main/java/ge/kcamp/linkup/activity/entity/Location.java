package ge.kcamp.linkup.activity.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.locationtech.jts.geom.Point;
import java.util.UUID;

@Setter
@Getter
@Entity
@Table(name = "locations")
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "location_id")
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false, unique = true)
    private Activity activity;

    /**
     * Nullable: a text-created plan can name a place ("Vake park") without coordinates,
     * since there is no geocoder yet. The column has never had a NOT NULL constraint -
     * {@code ddl-auto: validate} doesn't check nullability, so this annotation was simply
     * untrue. Readers must handle a null geometry (the map query filters those rows out;
     * without that, JDBC's getDouble turns the SQL NULL into 0.0 and the pin lands in the
     * Gulf of Guinea).
     */
    @Column(name = "geom_point", columnDefinition = "geometry(Point, 4326)")
    private Point geomPoint;

    @Column(name = "address_text", length = 255)
    private String addressText;

    public Location() {}

    public Location(UUID id, Activity activity, Point geomPoint, String addressText) {
        this.id = id;
        this.activity = activity;
        this.geomPoint = geomPoint;
        this.addressText = addressText;
    }

    public static LocationBuilder builder() {
        return new LocationBuilder();
    }

    public static class LocationBuilder {
        private UUID id;
        private Activity activity;
        private Point geomPoint;
        private String addressText;

        public LocationBuilder id(UUID id) { this.id = id; return this; }
        public LocationBuilder activity(Activity activity) { this.activity = activity; return this; }
        public LocationBuilder geomPoint(Point geomPoint) { this.geomPoint = geomPoint; return this; }
        public LocationBuilder addressText(String addressText) { this.addressText = addressText; return this; }

        public Location build() {
            return new Location(id, activity, geomPoint, addressText);
        }
    }
}
