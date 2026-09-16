package ge.kcamp.linkup.social;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the ordering mismatch that {@code chk_friendships_canonical_order} exposed.
 * <p>
 * Friendship rows are stored as one undirected edge with the "lower" id first. If Java
 * and PostgreSQL disagree about which id is lower, the constraint rejects inserts the
 * application believes are correctly ordered - and without the constraint, a query that
 * assumed database ordering would silently match nothing.
 */
class UuidCanonicalOrderTest {

    /** First hex digit >= 8, so its most significant bits are negative as a signed long. */
    private static final UUID HIGH_BIT_SET = UUID.fromString("aa57c8b0-73c4-458c-8d14-bd5ca4316fc7");
    private static final UUID HIGH_BIT_CLEAR = UUID.fromString("38677ada-c4b6-44b9-9051-3535c0151ab1");

    @Test
    void javasNaturalOrderingDisagreesWithByteOrdering() {
        // Documents the trap rather than endorsing it: compareTo says the aa... id is
        // smaller, because it compares the halves as signed longs.
        assertThat(HIGH_BIT_SET.compareTo(HIGH_BIT_CLEAR)).isNegative();
    }

    @Test
    void unsignedComparisonMatchesPostgresUuidOrdering() {
        assertThat(compareUnsigned(HIGH_BIT_SET, HIGH_BIT_CLEAR)).isPositive();
        assertThat(compareUnsigned(HIGH_BIT_CLEAR, HIGH_BIT_SET)).isNegative();
        assertThat(compareUnsigned(HIGH_BIT_SET, HIGH_BIT_SET)).isZero();
    }

    @Test
    void fallsBackToTheLowHalfWhenTheHighHalvesMatch() {
        UUID lower = UUID.fromString("aa57c8b0-73c4-458c-0000-000000000001");
        UUID higher = UUID.fromString("aa57c8b0-73c4-458c-ffff-ffffffffffff");

        assertThat(compareUnsigned(lower, higher)).isNegative();
    }

    /** Mirrors SocialGraphService.compareUnsigned, which is private. */
    private static int compareUnsigned(UUID x, UUID y) {
        int high = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
    }
}
