package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.activity.entity.Participant;
import ge.kcamp.linkup.activity.entity.ParticipantId;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * This was an empty {@code JpaRepository}: rows were only ever written by
 * {@code ActivityCommandHandler} at creation time and never read or updated, which
 * is why nobody could join an activity or answer an invitation.
 */
@Repository
public interface ParticipantRepository extends JpaRepository<Participant, ParticipantId> {

    Optional<Participant> findByIdActivityIdAndIdUserId(UUID activityId, UUID userId);

    List<Participant> findByIdActivityIdOrderByStatusAsc(UUID activityId);

    long countByIdActivityIdAndStatus(UUID activityId, ParticipantStatus status);

    /** Activity ids this user has some relationship with, filtered by status. */
    @Query("""
            SELECT p.id.activityId FROM Participant p
            WHERE p.id.userId = :userId AND p.status = :status
            """)
    List<UUID> findActivityIdsByUserAndStatus(
            @Param("userId") UUID userId, @Param("status") ParticipantStatus status);

    /**
     * Sets one person's status, inserting the row if it isn't there yet.
     * <p>
     * A single statement rather than the read-then-write it replaced, because joining is
     * documented as idempotent and the read-then-write only was when nobody else was
     * writing: two concurrent joins both saw no row, both inserted, and one lost on the
     * primary key - so a second tap came back 409 "that conflicts with something that
     * already exists" for an operation whose whole contract is that repeating it is
     * fine. Retrying in Java can't fix it either; a constraint violation poisons the
     * transaction it happens in.
     * <p>
     * Both RLS policies still apply: {@code participants_insert_policy} vets the insert
     * and {@code participants_update_policy} the conflict branch.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO participants (activity_id, user_id, status)
            VALUES (:activityId, :userId, :status)
            ON CONFLICT (activity_id, user_id) DO UPDATE SET status = EXCLUDED.status
            """, nativeQuery = true)
    void upsertStatus(
            @Param("activityId") UUID activityId,
            @Param("userId") UUID userId,
            @Param("status") String status);
}
