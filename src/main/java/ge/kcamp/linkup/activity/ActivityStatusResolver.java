package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * The one place that decides whether a plan is upcoming, happening now, or over.
 * <p>
 * The status is derived rather than stored, and derived here rather than in each caller.
 * Two things follow from that. It is always right - a backend that was down for an hour
 * comes back with correct answers instead of a table a scheduler never got to - and it
 * costs nothing to keep true, because there is nothing to keep.
 * <p>
 * What <em>is</em> stored is what the host did: {@code startedAt} and {@code endedAt}.
 * A host who starts early or ends early overrides the clock; a host who does nothing gets
 * the clock's answer, which is the common case and the one the rule below is written for.
 * <p>
 * Keep it in step with {@link ActivityStatusSql}, which says the same thing in SQL for the
 * queries that have to filter on it. If the two drift, a plan can be missing from the map
 * while its own detail screen calls it live.
 */
public final class ActivityStatusResolver {

    private ActivityStatusResolver() {
    }

    /**
     * How long after its start time a plan nobody touched starts by itself. Short enough
     * that "happening now" means it, long enough that a plan is not live while people are
     * still walking to it.
     */
    public static final Duration AUTO_START_AFTER = Duration.ofMinutes(5);

    /**
     * How long a plan runs when nothing says otherwise. An explicit end time wins over
     * this; most plans have none, and two hours is what a coffee, a run or a dinner
     * takes before "is this still on?" is the wrong question to ask.
     */
    public static final Duration DEFAULT_DURATION = Duration.ofHours(2);

    /**
     * Ceiling on how far a repeat rule is walked forward looking for the occurrence that
     * covers {@code now}. A daily plan started four years ago is the realistic worst
     * case; past this the answer is the last occurrence found, not an infinite loop.
     */
    private static final int MAX_OCCURRENCE_STEPS = 2_000;

    /**
     * Everything the rule reads. A record rather than seven parameters, because the
     * query side builds one per row and the entity side builds one per activity, and
     * both have to pass exactly the same things.
     *
     * @param hasTime false when only a date was given. Such a plan never starts by
     *                itself - midnight is not a start time anyone chose - so it stays
     *                upcoming until its host starts it or its day runs out.
     */
    public record Lifecycle(
            ZonedDateTime startTime,
            ZonedDateTime endTime,
            boolean hasTime,
            RepeatFrequency repeatFrequency,
            Integer repeatInterval,
            ZonedDateTime repeatUntil,
            ZonedDateTime startedAt,
            ZonedDateTime endedAt
    ) {
    }

    public static ActivityStatus resolve(Lifecycle plan, ZonedDateTime now) {
        if (plan.endedAt() != null) {
            return ActivityStatus.ENDED;
        }

        if (plan.startedAt() != null) {
            // The host started it, so the window runs from when they did, not from the
            // time on the plan - starting an hour late shouldn't end it an hour early.
            return now.isBefore(plan.startedAt().plus(duration(plan)))
                    ? ActivityStatus.LIVE
                    : ActivityStatus.ENDED;
        }

        ZonedDateTime occurrence = currentOccurrence(plan, now);
        if (!now.isBefore(autoEnd(plan, occurrence))) {
            return ActivityStatus.ENDED;
        }
        if (plan.hasTime() && !now.isBefore(occurrence.plus(AUTO_START_AFTER))) {
            return ActivityStatus.LIVE;
        }
        return ActivityStatus.UPCOMING;
    }

    /**
     * How long the plan runs. An end time that isn't after the start is treated as no end
     * time at all: the column is nullable and has been written by a parser, so "ends
     * before it begins" is a shape that can reach here, and a negative window would end
     * every such plan the moment it was created.
     */
    private static Duration duration(Lifecycle plan) {
        if (plan.endTime() == null || !plan.endTime().isAfter(plan.startTime())) {
            return DEFAULT_DURATION;
        }
        return Duration.between(plan.startTime(), plan.endTime());
    }

    /**
     * When an untouched plan is over. A plan with no clock time runs to the end of its
     * day rather than to a two-hour window from midnight, since the day is all the user
     * actually said.
     */
    private static ZonedDateTime autoEnd(Lifecycle plan, ZonedDateTime occurrence) {
        if (!plan.hasTime()) {
            return occurrence.toLocalDate().plusDays(1).atStartOfDay(occurrence.getZone());
        }
        return occurrence.plus(duration(plan));
    }

    /**
     * Which occurrence of a repeating plan {@code now} falls in - or, once the rule has
     * run out, the last one there was.
     * <p>
     * Without this a weekly plan would be permanently ended five minutes after its very
     * first Tuesday: the row stores the rule and the first occurrence, and nothing
     * materialises the rest (see {@code V24__activity_recurrence.sql}).
     */
    private static ZonedDateTime currentOccurrence(Lifecycle plan, ZonedDateTime now) {
        ZonedDateTime occurrence = plan.startTime();
        if (plan.repeatFrequency() == null) {
            return occurrence;
        }

        int interval = plan.repeatInterval() == null || plan.repeatInterval() < 1
                ? 1
                : plan.repeatInterval();

        for (int step = 0; step < MAX_OCCURRENCE_STEPS; step++) {
            if (now.isBefore(autoEnd(plan, occurrence))) {
                return occurrence;
            }
            ZonedDateTime next = advance(occurrence, plan.repeatFrequency(), interval);
            // Past the rule's end date there is no next one, and the plan is over for
            // good rather than upcoming forever.
            if (plan.repeatUntil() != null && next.isAfter(plan.repeatUntil())) {
                return occurrence;
            }
            occurrence = next;
        }
        return occurrence;
    }

    private static ZonedDateTime advance(ZonedDateTime from, RepeatFrequency frequency, int interval) {
        return switch (frequency) {
            case DAILY -> from.plusDays(interval);
            case WEEKLY -> from.plusWeeks(interval);
            case MONTHLY -> from.plusMonths(interval);
        };
    }
}
