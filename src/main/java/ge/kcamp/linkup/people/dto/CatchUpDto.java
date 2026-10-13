package ge.kcamp.linkup.people.dto;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.identity.UserSummary;

import java.time.ZonedDateTime;

/**
 * A friend the caller used to make plans with and hasn't for a while - one line of the
 * "catch up" list, with enough about the last time to suggest doing it again.
 *
 * @param lastTogether  the start of the last plan the two of them both took part in
 * @param daysSince     whole days between {@code lastTogether} and now
 * @param plansTogether how many plans the two did together in the look-back window; the
 *                      list leads with the people this is highest for
 * @param lastTitle     what that last plan was called
 * @param lastCategory  and what kind of plan it was, so "plan again" can start there
 */
public record CatchUpDto(
        UserSummary user,
        ZonedDateTime lastTogether,
        long daysSince,
        long plansTogether,
        String lastTitle,
        ActivityCategory lastCategory
) {
}
