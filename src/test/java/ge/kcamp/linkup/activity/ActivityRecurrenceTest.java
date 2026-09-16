package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.dto.CreateStructuredActivityRequest;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repeat rule's normalisation, which is the whole of the feature on this side: the
 * columns are only ever written together, and {@code chk_activities_repeat} turns any
 * inconsistent trio into a 409 about nothing the user did. No database needed - these
 * are the rules that decide what reaches it.
 */
class ActivityRecurrenceTest {

    private static final ZonedDateTime START = ZonedDateTime.parse("2031-03-04T15:00:00Z");

    private final ActivityFactory factory = new ActivityFactory();

    @Test
    void aFrequencyWithNoIntervalMeansEveryOne() {
        CreateStructuredActivityRequest request = request(RepeatFrequency.WEEKLY, null, null);

        assertThat(request.resolvedRepeatInterval()).isEqualTo(1);
        assertThat(request.resolvedRepeatUntil()).isNull();
    }

    @Test
    void anIntervalAndEndDateWithoutAFrequencyAreDropped() {
        CreateStructuredActivityRequest request =
                request(null, 3, START.plusMonths(2));

        assertThat(request.resolvedRepeatInterval()).isNull();
        assertThat(request.resolvedRepeatUntil()).isNull();
    }

    @Test
    void aRuleEndingBeforeItStartsIsRejected() {
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = validatorFactory.getValidator();

            assertThat(validator.validate(request(RepeatFrequency.WEEKLY, 1, START.minusDays(1))))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("repeatWindowOrdered");

            assertThat(validator.validate(request(RepeatFrequency.WEEKLY, 1, START.plusDays(1))))
                    .isEmpty();
        }
    }

    @Test
    void theFactoryWritesTheThreeColumnsTogetherOrNotAtAll() {
        Activity repeating = factory.createFrom(
                spec(RepeatFrequency.MONTHLY, 2, START.plusYears(1)),
                UUID.randomUUID(), ActivityVisibility.FRIENDS, null, null);

        assertThat(repeating.getRepeatFrequency()).isEqualTo(RepeatFrequency.MONTHLY);
        assertThat(repeating.getRepeatInterval()).isEqualTo(2);
        assertThat(repeating.getRepeatUntil()).isEqualTo(START.plusYears(1));

        // A spec carrying leftovers with no frequency - the shape the edit form sends
        // when the user switches a repeating plan back to one-off.
        Activity oneOff = factory.createFrom(
                spec(null, 2, START.plusYears(1)),
                UUID.randomUUID(), ActivityVisibility.FRIENDS, null, null);

        assertThat(oneOff.getRepeatFrequency()).isNull();
        assertThat(oneOff.getRepeatInterval()).isNull();
        assertThat(oneOff.getRepeatUntil()).isNull();
    }

    private static CreateStructuredActivityRequest request(
            RepeatFrequency frequency, Integer interval, ZonedDateTime until) {
        return new CreateStructuredActivityRequest(
                "Football", START, null, true, 41.7151, 44.8271, "Rustaveli",
                ActivityVisibility.FRIENDS, null, List.of(), null,
                frequency, interval, until);
    }

    private static StructuredEventSpec spec(
            RepeatFrequency frequency, Integer interval, ZonedDateTime until) {
        return new StructuredEventSpec(
                "Football", START, null, true, "Rustaveli", 41.7151, 44.8271,
                frequency, interval, until);
    }
}
