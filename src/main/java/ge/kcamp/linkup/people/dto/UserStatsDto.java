package ge.kcamp.linkup.people.dto;

import ge.kcamp.linkup.identity.UserSummary;

import java.util.List;

/**
 * The caller's own year: {@code GET /me/stats}. Every count is of plans that happened -
 * ended, after the host started them - and that the caller hosted or joined.
 *
 * @param firstYear          the year the account was made, in the caller's zone, so the
 *                           client knows which years to offer.
 * @param previousYearTotals the year before, for the "+N vs last year" deltas; null when
 *                           that year predates the account.
 * @param byMonth            twelve entries, January first.
 * @param byCategory         every category they did, most first.
 * @param topCompanions      who they went with most, most first, at most eight.
 * @param byWeekday          seven counts, Monday first.
 * @param byTimeOfDay        by start time in the caller's zone. Plans with only a date
 *                           have no time of day and aren't counted here.
 * @param topPlaces          where they went most, at most five.
 */
public record UserStatsDto(
        int year,
        int firstYear,
        Totals totals,
        Totals previousYearTotals,
        List<MonthCount> byMonth,
        List<CategoryCountDto> byCategory,
        List<Companion> topCompanions,
        List<Long> byWeekday,
        TimeOfDay byTimeOfDay,
        List<PlaceCount> topPlaces
) {

    public record Totals(long plans, long hosted, long newFriends) {
    }

    public record MonthCount(long hosted, long joined) {
    }

    public record Companion(UserSummary user, long count) {
    }

    /** Morning before 12:00, afternoon 12:00-17:00, evening from 17:00. */
    public record TimeOfDay(long morning, long afternoon, long evening) {
    }

    /**
     * @param name the place line up to its first comma - "Mtatsminda Park".
     * @param area what follows it, if anything - "Tbilisi".
     */
    public record PlaceCount(String name, String area, long count) {
    }
}
