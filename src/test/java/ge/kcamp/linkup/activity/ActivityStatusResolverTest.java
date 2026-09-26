package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.ActivityStatusResolver.Lifecycle;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle rule, which is the whole of the feature on this side: nothing is stored
 * but what the host did, so every status a user ever sees comes out of this class. No
 * database needed - the clock is the only input that isn't on the row.
 */
class ActivityStatusResolverTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2031-03-04T15:00:00Z");

    @Test
    void aPlanStartsOutUpcoming() {
        assertThat(resolve(plan(NOW.plusHours(3)))).isEqualTo(ActivityStatus.UPCOMING);
    }

    @Test
    void itNeverStartsByItself() {
        // Only the host says a plan is happening; the clock passing its start time
        // doesn't put anyone in the room.
        assertThat(resolve(plan(NOW.minusMinutes(5)))).isEqualTo(ActivityStatus.UPCOMING);
        assertThat(resolve(plan(NOW.minusMinutes(119)))).isEqualTo(ActivityStatus.UPCOMING);
    }

    @Test
    void aPlanNobodyStartedIsCancelledTwoHoursIn() {
        assertThat(resolve(plan(NOW.minusHours(2)))).isEqualTo(ActivityStatus.CANCELLED);
        assertThat(resolve(plan(NOW.minusDays(3)))).isEqualTo(ActivityStatus.CANCELLED);
    }

    @Test
    void theHostCancellingItCancelsItWhateverElseHappened() {
        Lifecycle cancelled = new Lifecycle(
                NOW.plusHours(5), null, true, null, null, null, null, null, NOW.minusMinutes(1));
        assertThat(resolve(cancelled)).isEqualTo(ActivityStatus.CANCELLED);

        Lifecycle cancelledWhileLive = new Lifecycle(
                NOW.minusMinutes(30), null, true, null, null, null, NOW.minusMinutes(30), null, NOW);
        assertThat(resolve(cancelledWhileLive)).isEqualTo(ActivityStatus.CANCELLED);
    }

    @Test
    void theHostStartingItMakesItLiveUntilItsWindowRunsOut() {
        Lifecycle startedEarly = started(NOW.plusHours(2), null, NOW.minusMinutes(30));
        assertThat(resolve(startedEarly)).isEqualTo(ActivityStatus.LIVE);

        Lifecycle startedLongAgo = started(NOW.plusHours(2), null, NOW.minusHours(3));
        assertThat(resolve(startedLongAgo)).isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void aLateStartStillRescuesAPlanTheClockCancelled() {
        Lifecycle startedLate = started(NOW.minusHours(4), null, NOW.minusMinutes(10));
        assertThat(resolve(startedLate)).isEqualTo(ActivityStatus.LIVE);
    }

    @Test
    void anExplicitEndTimeWinsOverTheTwoHourDefault() {
        Lifecycle longPlan = started(NOW.minusHours(3), NOW.plusHours(1), NOW.minusHours(3));
        assertThat(resolve(longPlan)).isEqualTo(ActivityStatus.LIVE);

        Lifecycle shortPlan = started(NOW.minusHours(1), NOW.minusMinutes(10), NOW.minusHours(1));
        assertThat(resolve(shortPlan)).isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void anEndTimeThatIsNotAfterTheStartIsIgnoredRatherThanEndingItAtOnce() {
        // The column is nullable and has been written by a parser, so this shape reaches
        // here; read as a window it would be negative and end every such plan on start.
        assertThat(resolve(started(NOW.minusMinutes(30), NOW.minusHours(4), NOW.minusMinutes(30))))
                .isEqualTo(ActivityStatus.LIVE);
    }

    @Test
    void theHostEndingItEndsItWhateverTheClockSays() {
        Lifecycle ended = new Lifecycle(
                NOW.plusHours(5), null, true, null, null, null, NOW.minusMinutes(20), NOW.minusMinutes(1), null);
        assertThat(resolve(ended)).isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void aDateOnlyPlanNeverStartsByItselfAndIsCancelledAtTheEndOfItsDay() {
        // has_time false means the start time is midnight, which nobody chose - so there
        // is no two-hour grace from it, only the day.
        ZonedDateTime midnight = NOW.toLocalDate().atStartOfDay(NOW.getZone());
        Lifecycle allDay = new Lifecycle(midnight, null, false, null, null, null, null, null, null);

        assertThat(resolve(allDay)).isEqualTo(ActivityStatus.UPCOMING);
        assertThat(ActivityStatusResolver.resolve(allDay, midnight.plusDays(1)))
                .isEqualTo(ActivityStatus.CANCELLED);
    }

    @Test
    void aDateOnlyPlanEastOfUtcRunsToTheEndOfItsOwnDayNotUtcs() {
        // Stored at Tbilisi midnight and read back from Postgres in UTC, i.e. 20:00Z the
        // evening before. Taking the date in the timestamp's zone ended it at 04:00 local
        // on its own day.
        ZonedDateTime tbilisiMidnightInUtc = ZonedDateTime.parse("2031-03-03T20:00:00Z");
        Lifecycle allDay = new Lifecycle(
                tbilisiMidnightInUtc, null, false, null, null, null, null, null, null);

        ZonedDateTime tbilisiMidday = ZonedDateTime.parse("2031-03-04T08:00:00Z");
        assertThat(ActivityStatusResolver.resolve(allDay, tbilisiMidday))
                .isEqualTo(ActivityStatus.UPCOMING);
        assertThat(ActivityStatusResolver.resolve(allDay, tbilisiMidnightInUtc.plusDays(1).minusMinutes(1)))
                .isEqualTo(ActivityStatus.UPCOMING);
        assertThat(ActivityStatusResolver.resolve(allDay, tbilisiMidnightInUtc.plusDays(1)))
                .isEqualTo(ActivityStatus.CANCELLED);
    }

    @Test
    void aRepeatingPlanSkipsAMissedOccurrenceRatherThanCancellingTheSeries() {
        // The row stores the rule and the first occurrence; nothing materialises the
        // rest, so reading start_time literally would cancel a weekly plan for good two
        // hours into its first Tuesday.
        Lifecycle weekly = new Lifecycle(
                NOW.minusWeeks(6).plusHours(1), null, true, RepeatFrequency.WEEKLY, 1, null, null, null, null);

        assertThat(resolve(weekly)).isEqualTo(ActivityStatus.UPCOMING);
    }

    @Test
    void aRepeatingPlanIsUpcomingDuringAnOccurrenceNobodyStartedYet() {
        Lifecycle daily = new Lifecycle(
                NOW.minusDays(10).minusMinutes(30), null, true, RepeatFrequency.DAILY, 1, null, null, null, null);

        assertThat(resolve(daily)).isEqualTo(ActivityStatus.UPCOMING);
    }

    @Test
    void startingOneOccurrenceOfARepeatingPlanDoesNotEndTheSeries() {
        ZonedDateTime firstWeek = NOW.minusWeeks(3);
        // Started an hour early for the first week, and left to run out its window.
        Lifecycle weekly = new Lifecycle(
                firstWeek, null, true, RepeatFrequency.WEEKLY, 1, null,
                firstWeek.minusHours(1), null, null);

        assertThat(ActivityStatusResolver.resolve(weekly, firstWeek.plusMinutes(30)))
                .isEqualTo(ActivityStatus.LIVE);
        // Once that run is over the series is waiting on next week, not over.
        assertThat(ActivityStatusResolver.resolve(weekly, firstWeek.plusHours(2)))
                .isEqualTo(ActivityStatus.UPCOMING);
        assertThat(resolve(weekly)).isEqualTo(ActivityStatus.UPCOMING);
    }

    @Test
    void aStartedRepeatingPlanIsLiveDuringTheRunTheHostStarted() {
        ZonedDateTime thisWeek = NOW.minusMinutes(20);
        Lifecycle weekly = new Lifecycle(
                thisWeek.minusWeeks(4), null, true, RepeatFrequency.WEEKLY, 1, null, thisWeek, null, null);

        assertThat(resolve(weekly)).isEqualTo(ActivityStatus.LIVE);
    }

    @Test
    void aRepeatRuleThatHasRunOutWithItsLastOccurrenceMissedIsCancelled() {
        Lifecycle finished = new Lifecycle(
                NOW.minusWeeks(6), null, true, RepeatFrequency.WEEKLY, 1, NOW.minusWeeks(2), null, null, null);

        assertThat(resolve(finished)).isEqualTo(ActivityStatus.CANCELLED);
    }

    @Test
    void aRepeatRuleThatHasRunOutWithItsLastOccurrenceStartedHasEnded() {
        ZonedDateTime last = NOW.minusWeeks(2);
        Lifecycle finished = new Lifecycle(
                NOW.minusWeeks(6), null, true, RepeatFrequency.WEEKLY, 1, last, last, null, null);

        assertThat(resolve(finished)).isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void onlyEndedAndCancelledAreOver() {
        assertThat(ActivityStatus.UPCOMING.isOver()).isFalse();
        assertThat(ActivityStatus.LIVE.isOver()).isFalse();
        assertThat(ActivityStatus.ENDED.isOver()).isTrue();
        assertThat(ActivityStatus.CANCELLED.isOver()).isTrue();
    }

    private static ActivityStatus resolve(Lifecycle plan) {
        return ActivityStatusResolver.resolve(plan, NOW);
    }

    private static Lifecycle plan(ZonedDateTime startTime) {
        return new Lifecycle(startTime, null, true, null, null, null, null, null, null);
    }

    private static Lifecycle started(ZonedDateTime startTime, ZonedDateTime endTime, ZonedDateTime startedAt) {
        return new Lifecycle(startTime, endTime, true, null, null, null, startedAt, null, null);
    }
}
