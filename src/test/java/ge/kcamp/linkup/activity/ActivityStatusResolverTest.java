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
    void itIsStillUpcomingInTheFiveMinutesAfterItsStartTime() {
        // People are still walking to it; "happening now" would be a claim about a room
        // that is empty.
        assertThat(resolve(plan(NOW.minusMinutes(4)))).isEqualTo(ActivityStatus.UPCOMING);
    }

    @Test
    void itStartsByItselfFiveMinutesIn() {
        assertThat(resolve(plan(NOW.minusMinutes(5)))).isEqualTo(ActivityStatus.LIVE);
        assertThat(resolve(plan(NOW.minusMinutes(90)))).isEqualTo(ActivityStatus.LIVE);
    }

    @Test
    void itEndsByItselfTwoHoursIn() {
        assertThat(resolve(plan(NOW.minusHours(2)))).isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void anExplicitEndTimeWinsOverTheTwoHourDefault() {
        Lifecycle longPlan = withEnd(NOW.minusHours(3), NOW.plusHours(1));
        assertThat(resolve(longPlan)).isEqualTo(ActivityStatus.LIVE);

        Lifecycle shortPlan = withEnd(NOW.minusHours(1), NOW.minusMinutes(10));
        assertThat(resolve(shortPlan)).isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void anEndTimeThatIsNotAfterTheStartIsIgnoredRatherThanEndingItAtOnce() {
        // The column is nullable and has been written by a parser, so this shape reaches
        // here; read as a window it would be negative and end every such plan on creation.
        assertThat(resolve(withEnd(NOW.minusMinutes(30), NOW.minusHours(4))))
                .isEqualTo(ActivityStatus.LIVE);
    }

    @Test
    void theHostStartingItEarlyMakesItLiveAndMovesTheWindow() {
        Lifecycle started = new Lifecycle(
                NOW.plusHours(2), null, true, null, null, null, NOW.minusMinutes(30), null);
        assertThat(resolve(started)).isEqualTo(ActivityStatus.LIVE);

        Lifecycle startedLongAgo = new Lifecycle(
                NOW.plusHours(2), null, true, null, null, null, NOW.minusHours(3), null);
        assertThat(resolve(startedLongAgo)).isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void theHostEndingItEndsItWhateverTheClockSays() {
        Lifecycle ended = new Lifecycle(
                NOW.plusHours(5), null, true, null, null, null, null, NOW.minusMinutes(1));
        assertThat(resolve(ended)).isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void aDateOnlyPlanNeverStartsByItselfAndRunsToTheEndOfItsDay() {
        // has_time false means the start time is midnight, which nobody chose - starting
        // it five minutes later would call every all-day plan live at 00:05.
        ZonedDateTime midnight = NOW.toLocalDate().atStartOfDay(NOW.getZone());
        Lifecycle allDay = new Lifecycle(midnight, null, false, null, null, null, null, null);

        assertThat(resolve(allDay)).isEqualTo(ActivityStatus.UPCOMING);
        assertThat(ActivityStatusResolver.resolve(allDay, midnight.plusDays(1)))
                .isEqualTo(ActivityStatus.ENDED);
    }

    @Test
    void aRepeatingPlanIsNotEndedForeverByItsFirstOccurrence() {
        // The row stores the rule and the first occurrence; nothing materialises the
        // rest, so reading start_time literally would end a weekly plan permanently two
        // hours into its first Tuesday.
        Lifecycle weekly = new Lifecycle(
                NOW.minusWeeks(6).plusHours(1), null, true, RepeatFrequency.WEEKLY, 1, null, null, null);

        assertThat(resolve(weekly)).isEqualTo(ActivityStatus.UPCOMING);
    }

    @Test
    void aRepeatingPlanIsLiveDuringAnOccurrenceThatIsNotItsFirst() {
        Lifecycle daily = new Lifecycle(
                NOW.minusDays(10).minusMinutes(30), null, true, RepeatFrequency.DAILY, 1, null, null, null);

        assertThat(resolve(daily)).isEqualTo(ActivityStatus.LIVE);
    }

    @Test
    void aRepeatRuleThatHasRunOutIsOver() {
        Lifecycle finished = new Lifecycle(
                NOW.minusWeeks(6), null, true, RepeatFrequency.WEEKLY, 1, NOW.minusWeeks(2), null, null);

        assertThat(resolve(finished)).isEqualTo(ActivityStatus.ENDED);
    }

    private static ActivityStatus resolve(Lifecycle plan) {
        return ActivityStatusResolver.resolve(plan, NOW);
    }

    private static Lifecycle plan(ZonedDateTime startTime) {
        return new Lifecycle(startTime, null, true, null, null, null, null, null);
    }

    private static Lifecycle withEnd(ZonedDateTime startTime, ZonedDateTime endTime) {
        return new Lifecycle(startTime, endTime, true, null, null, null, null, null);
    }
}
