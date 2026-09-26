package ge.kcamp.linkup.people.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The two facts about another person that the caller's own connection can't read, through
 * the V35 SECURITY DEFINER functions. See {@code V35__friend_profile.sql} for why each one
 * has to go around row-level security, and what it is careful to hand back.
 */
@Repository
public class PeopleQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PeopleQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Counts(long friends, long hosted, long groups) {
    }

    /** Anyone's friend, hosted-plan and group counts - numbers only. */
    public Counts countsFor(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT friend_count, hosted_count, group_count FROM app_user_profile_counts(:userId)",
                Map.of("userId", userId),
                (rs, rowNum) -> new Counts(
                        rs.getLong("friend_count"), rs.getLong("hosted_count"), rs.getLong("group_count")));
    }

    /**
     * The caller's friends who are also friends with {@code otherId}. The caller is the
     * connection's own user ({@code app_current_user()}), not a parameter.
     */
    public List<UUID> mutualFriendIds(UUID otherId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM app_mutual_friends(:otherId)", Map.of("otherId", otherId), UUID.class);
    }
}
