package ge.kcamp.linkup.activity.repository;

import ge.kcamp.linkup.activity.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    /**
     * Plans that may have an occurrence starting in {@code (from, to]}: one-offs whose
     * start is in the window, and repeating plans whose rule hasn't run out by
     * {@code from}. A superset - the caller finds the actual next start with
     * {@code ActivityStatusResolver.nextStartAfter}.
     * <p>
     * Only plans with a clock time: a date-only plan is stored at local midnight, and a
     * reminder for it would go out at half past eleven the night before. And only plans
     * the host hasn't started or ended, since either one ends the plan's run for good.
     */
    @Query("""
            SELECT a FROM Activity a
            WHERE a.hasTime = true
              AND a.startedAt IS NULL AND a.endedAt IS NULL
              AND a.startTime <= :to
              AND ((a.repeatFrequency IS NULL AND a.startTime > :from)
                   OR (a.repeatFrequency IS NOT NULL AND (a.repeatUntil IS NULL OR a.repeatUntil > :from)))
            """)
    List<Activity> findReminderCandidates(@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);
}
