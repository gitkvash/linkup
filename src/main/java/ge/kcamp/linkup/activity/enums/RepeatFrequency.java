package ge.kcamp.linkup.activity.enums;

/**
 * How often a repeating plan comes round, paired with
 * {@code activities.repeat_interval} ("every 2 WEEKLY" = fortnightly).
 * <p>
 * A null frequency - not a value here - is what makes a plan one-off, so there is no
 * {@code NONE} constant: it would be a second way to say the same thing, and the
 * database's {@code chk_activities_repeat} would then have to allow an interval
 * alongside it.
 */
public enum RepeatFrequency {
    DAILY,
    WEEKLY,
    MONTHLY
}
