package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * The one place that decides whether a plan is upcoming, happening now, over, or
 * cancelled.
 * <p>
 * The status is derived rather than stored, and derived here rather than in each caller.
 * Two things follow from that. It is always right - a backend that was down for an hour
 * comes back with correct answers instead of a table a scheduler never got to - and it
 * costs nothing to keep true, because there is nothing to keep.
 * <p>
 * What <em>is</em> stored is what the host did: {@code startedAt}, {@code endedAt} and
 * {@code cancelledAt}. A plan never starts by the clock - it is live only once its host
 * says so. One that nobody started within {@link #START_GRACE} of its start time didn't
 * happen, and reads as cancelled.
 * <p>
 * Keep it in step with {@link ActivityStatusSql}, which says the same thing in SQL for the
 * queries that have to filter on it. If the two drift, a plan can be missing from the map
 * while its own detail screen calls it live.
 */
public final class ActivityStatusResolver {

    private ActivityStatusResolver() {
    }

    /**
     * How long after its start time a plan nobody started stays upcoming. Past this it is
     * cancelled: two hours late is not "running late" any more, and a plan left upcoming
     * forever would sit among the real ones in every list.
     */
    public static final Duration START_GRACE = Duration.ofHours(2);

    /**
     * How long a started plan runs when nothing says otherwise. An explicit end time wins
     * over this; most plans have none, and two hours is what a coffee, a run or a dinner
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
     * Everything the rule reads. A record rather than nine parameters, because the
     * query side builds one per row and the entity side builds one per activity, and
     * both have to pass exactly the same things.
     *
     * @param hasTime false when only a date was given. Such a plan has no start time to
     *                be late for, so it stays upcoming until its host starts it or its
     *                day runs out.
     */
    public record Lifecycle(
            ZonedDateTime startTime,
            ZonedDateTime endTime,
            boolean hasTime,
            RepeatFrequency repeatFrequency,
            Integer repeatInterval,
            ZonedDateTime repeatUntil,
            ZonedDateTime startedAt,
            ZonedDateTime endedAt,
            ZonedDateTime cancelledAt
    ) {
        public static Lifecycle of(Activity activity) {
            return new Lifecycle(
                    activity.getStartTime(), activity.getEndTime(), activity.isHasTime(),
                    activity.getRepeatFrequency(), activity.getRepeatInterval(), activity.getRepeatUntil(),
                    activity.getStartedAt(), activity.getEndedAt(), activity.getCancelledAt());
        }
    }

    public static ActivityStatus resolve(Lifecycle plan, ZonedDateTime now) {
        if (plan.cancelledAt() != null) {
            return ActivityStatus.CANCELLED;
        }

        if (plan.startedAt() != null) {
            // The host started it, so the window runs from when they did, not from the
            // time on the plan - starting an hour late shouldn't end it an hour early.
            if (plan.endedAt() == null && now.isBefore(plan.startedAt().plus(duration(plan)))) {
                return ActivityStatus.LIVE;
            }
            // Their run is over. For a one-off that is the end of it; a repeating plan
            // goes on to the occurrence after the one they started.
            ZonedDateTime following = followingOccurrence(plan);
            return following == null ? ActivityStatus.ENDED : untouched(plan, now, following);
        }

        if (plan.endedAt() != null) {
            return ActivityStatus.ENDED;
        }
        return untouched(plan, now, plan.startTime());
    }

    /**
     * Occurrences nobody has started, from {@code first} on: upcoming until the current
     * one's grace runs out. A repeating plan skips a missed occurrence and waits for the
     * next; only the last one there is, missed, cancels the plan.
     */
    private static ActivityStatus untouched(Lifecycle plan, ZonedDateTime now, ZonedDateTime first) {
        ZonedDateTime occurrence = currentOccurrence(plan, now, first);
        return now.isBefore(deadline(plan, occurrence))
                ? ActivityStatus.UPCOMING
                : ActivityStatus.CANCELLED;
    }

    /**
     * The occurrence after the one the host started, or null if there is none. The one
     * they started is whichever was current when they pressed start - so starting a
     * weekly plan an hour early counts for that week, not the week before.
     */
    private static ZonedDateTime followingOccurrence(Lifecycle plan) {
        if (plan.repeatFrequency() == null) {
            return null;
        }
        ZonedDateTime started = currentOccurrence(plan, plan.startedAt(), plan.startTime());
        return nextStartAfter(plan, started);
    }

    /**
     * How long a started plan runs. An end time that isn't after the start is treated as
     * no end time at all: the column is nullable and has been written by a parser, so
     * "ends before it begins" is a shape that can reach here, and a negative window would
     * end every such plan the moment it was started.
     */
    private static Duration duration(Lifecycle plan) {
        if (plan.endTime() == null || !plan.endTime().isAfter(plan.startTime())) {
            return DEFAULT_DURATION;
        }
        return Duration.between(plan.startTime(), plan.endTime());
    }

    /**
     * When an occurrence nobody started stops being upcoming: {@link #START_GRACE} after
     * its start. A plan with no clock time gets the whole of its day instead, since the
     * day is all the user actually said.
     * <p>
     * The end of its day is a day after its start, not midnight in the timestamp's zone.
     * The creator's zone is not stored, and a {@code timestamptz} comes back from
     * Postgres in UTC: taking the date there put a Tbilisi plan's end at 04:00 local on
     * its own day, so every all-day plan east of UTC read as over by breakfast. Every
     * create and edit path stores a date-only plan at the creator's local midnight
     * (see {@code ActivityCommandHandler}), so start plus a day is that day's end
     * wherever the plan was made - an hour off only across a DST change.
     */
    private static ZonedDateTime deadline(Lifecycle plan, ZonedDateTime occurrence) {
        if (!plan.hasTime()) {
            return occurrence.plusDays(1);
        }
        return occurrence.plus(START_GRACE);
    }

    /**
     * Which occurrence, from {@code first} on, {@code now} falls in - or, once the rule
     * has run out, the last one there was.
     * <p>
     * Without this a weekly plan would be cancelled for good two hours after its very
     * first Tuesday: the row stores the rule and the first occurrence, and nothing
     * materialises the rest (see {@code V24__activity_recurrence.sql}).
     */
    private static ZonedDateTime currentOccurrence(Lifecycle plan, ZonedDateTime now, ZonedDateTime first) {
        ZonedDateTime occurrence = first;
        if (plan.repeatFrequency() == null) {
            return occurrence;
        }

        int interval = intervalOf(plan);

        for (int step = 0; step < MAX_OCCURRENCE_STEPS; step++) {
            if (now.isBefore(deadline(plan, occurrence))) {
                return occurrence;
            }
            ZonedDateTime next = advance(occurrence, plan.repeatFrequency(), interval);
            // Past the rule's end date there is no next one, and the plan is done for
            // good rather than upcoming forever.
            if (plan.repeatUntil() != null && next.isAfter(plan.repeatUntil())) {
                return occurrence;
            }
            occurrence = next;
        }
        return occurrence;
    }

    /**
     * The first time the plan starts after {@code after}, or null if it never starts
     * again: a one-off whose start has passed, or a repeat rule that has run out.
     * <p>
     * The next start, where {@link #currentOccurrence} is the occurrence covering a
     * moment - what the start-of-plan reminders need, since "starts in 30 minutes" is
     * about an occurrence that hasn't begun. Says nothing about whether the host has
     * already started, ended or cancelled the plan; the caller checks that.
     */
    public static ZonedDateTime nextStartAfter(Lifecycle plan, ZonedDateTime after) {
        ZonedDateTime occurrence = plan.startTime();
        if (plan.repeatFrequency() == null) {
            return occurrence.isAfter(after) ? occurrence : null;
        }

        int interval = intervalOf(plan);
        for (int step = 0; step < MAX_OCCURRENCE_STEPS; step++) {
            if (plan.repeatUntil() != null && occurrence.isAfter(plan.repeatUntil())) {
                return null;
            }
            if (occurrence.isAfter(after)) {
                return occurrence;
            }
            occurrence = advance(occurrence, plan.repeatFrequency(), interval);
        }
        return null;
    }

    private static int intervalOf(Lifecycle plan) {
        return plan.repeatInterval() == null || plan.repeatInterval() < 1
                ? 1
                : plan.repeatInterval();
    }

    private static ZonedDateTime advance(ZonedDateTime from, RepeatFrequency frequency, int interval) {
        return switch (frequency) {
            case DAILY -> from.plusDays(interval);
            case WEEKLY -> from.plusWeeks(interval);
            case MONTHLY -> from.plusMonths(interval);
        };
    }
}
