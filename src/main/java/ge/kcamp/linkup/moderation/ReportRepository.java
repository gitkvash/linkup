package ge.kcamp.linkup.moderation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Only {@link #file} is used. The app never reads a report back, and the row is written by one
 * statement that can quietly decline a repeat.
 */
interface ReportRepository extends JpaRepository<ReportRow, UUID> {

    /**
     * Adds the report unless this reporter already reported this target.
     *
     * @return 1 if it was new, 0 if it repeated an earlier report
     */
    @Modifying
    @Query(value = """
            INSERT INTO reports (report_id, reporter_id, target_type, target_id, reason, details)
            VALUES (:id, :reporter, :targetType, :targetId, :reason, :details)
            ON CONFLICT (reporter_id, target_type, target_id) DO NOTHING
            """, nativeQuery = true)
    int file(@Param("id") UUID id,
             @Param("reporter") UUID reporter,
             @Param("targetType") String targetType,
             @Param("targetId") UUID targetId,
             @Param("reason") String reason,
             @Param("details") String details);
}
