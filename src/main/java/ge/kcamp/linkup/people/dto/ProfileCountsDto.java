package ge.kcamp.linkup.people.dto;

/**
 * The inline line under a profile's bio.
 *
 * @param hosted plans they created that were started and not cancelled - see {@code V35__friend_profile.sql}.
 */
public record ProfileCountsDto(long friends, long hosted, long groups) {
}
