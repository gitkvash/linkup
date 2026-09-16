package ge.kcamp.linkup.feed;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed's ordering key. These properties are what make cursor pagination terminate,
 * so they're worth pinning down.
 */
class FeedTimelineScoreTest {

    @Test
    void ordersByStartTime() {
        Instant earlier = Instant.parse("2030-01-01T18:00:00Z");
        Instant later = Instant.parse("2030-01-02T18:00:00Z");
        UUID id = UUID.randomUUID();

        assertThat(FeedTimelineScore.of(id, earlier)).isLessThan(FeedTimelineScore.of(id, later));
    }

    @Test
    void breaksTiesSoActivitiesSharingAMillisecondGetDistinctScores() {
        // The realistic case: a time picker yields minute-granular values, so plans
        // routinely share an exact start instant. With a start-time-only score, an
        // exclusive cursor skipped them and an inclusive one repeated them.
        Instant sameInstant = Instant.parse("2030-01-01T19:00:00Z");

        Set<Double> scores = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            scores.add(FeedTimelineScore.of(UUID.randomUUID(), sameInstant));
        }

        // 10 tie-break bits, so 200 ids should spread across many distinct scores.
        assertThat(scores).hasSizeGreaterThan(100);
    }

    @Test
    void isDeterministic() {
        UUID id = UUID.fromString("9e48c4f9-ceb9-4f77-a850-6595fd15b643");
        Instant instant = Instant.parse("2030-01-01T19:00:00Z");

        assertThat(FeedTimelineScore.of(id, instant)).isEqualTo(FeedTimelineScore.of(id, instant));
    }

    @Test
    void staysExactlyRepresentableAsADouble() {
        // Redis sorted-set scores are IEEE doubles: above 2^53 they stop being exact,
        // which would make the cursor comparison unreliable.
        double score = FeedTimelineScore.of(UUID.randomUUID(), Instant.parse("2100-01-01T00:00:00Z"));

        assertThat(score).isLessThan(9.007199254740992E15);
        assertThat(score).isEqualTo(Math.rint(score));
    }

    @Test
    void recoversTheStartMillisFloorForSqlPrefiltering() {
        Instant instant = Instant.parse("2030-01-01T19:00:00Z");
        double score = FeedTimelineScore.of(UUID.randomUUID(), instant);

        assertThat(FeedTimelineScore.startMillisOf(score)).isEqualTo(instant.toEpochMilli());
    }
}
