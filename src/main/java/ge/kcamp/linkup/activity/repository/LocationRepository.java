package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.activity.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LocationRepository extends JpaRepository<Location, UUID> {

    /**
     * The one location row an activity may have ({@code locations.activity_id} is
     * unique). Needed by the edit path, which has to update the existing row rather
     * than insert a second one.
     */
    Optional<Location> findByActivityId(UUID activityId);
}
