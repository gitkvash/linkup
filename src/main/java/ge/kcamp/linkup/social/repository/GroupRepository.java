package ge.kcamp.linkup.social.repository;

import ge.kcamp.linkup.social.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GroupRepository extends JpaRepository<Group, UUID> {

    List<Group> findByOwnerId(UUID ownerId);
}
