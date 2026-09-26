package ge.kcamp.linkup.people;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.DatabaseRole;
import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.people.dto.UserProfileDto;
import ge.kcamp.linkup.people.dto.UserStatsDto;
import ge.kcamp.linkup.social.CommonGroup;
import ge.kcamp.linkup.social.Relationship;
import ge.kcamp.linkup.social.SocialGraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The friend profile and the stats screen against the real schema and the restricted app
 * role - which is the whole point: every count here was wrong in a way no unit test could
 * see until V35 moved it behind a SECURITY DEFINER function, because a request connection
 * only sees the caller's own friendships and memberships.
 */
@SpringBootTest
class PeopleIT extends AbstractIntegrationTest {

    @Autowired
    private PersonProfileService personProfileService;

    @Autowired
    private UserStatsService userStatsService;

    @Autowired
    private SocialGraphService socialGraphService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void countsAndMutualFriendsAreTheOtherPersonsNotTheCallersSliceOfThem() {
        UUID alice = newUser();
        UUID bob = newUser();
        UUID carol = newUser();
        UUID dave = newUser();
        friends(alice, bob);
        friends(alice, carol);
        friends(bob, carol);
        friends(bob, dave);
        UUID shared = group(alice, alice, bob);
        group(bob, bob, dave);

        actAs(alice);
        UserProfileDto profile = personProfileService.profile(alice, bob).orElseThrow();

        assertThat(profile.relationship()).isEqualTo(Relationship.Kind.FRIENDS);
        // Bob's three friends and two groups, not the one friendship and one group Alice's
        // own connection can see of his.
        assertThat(profile.stats().friends()).isEqualTo(3);
        assertThat(profile.stats().groups()).isEqualTo(2);
        assertThat(profile.mutualFriends()).extracting(UserSummary::userId).containsExactly(carol);
        assertThat(profile.groupsInCommon()).extracting(CommonGroup::groupId).containsExactly(shared);
        assertThat(profile.groupsInCommon().getFirst().memberCount()).isEqualTo(2);
    }

    @Test
    void aBlockEitherWayLooksLikeNoSuchPerson() {
        UUID alice = newUser();
        UUID eve = newUser();
        DatabaseRole.runAsSystem(() -> jdbcTemplate.update("""
                INSERT INTO friendships (user_a_id, user_b_id, status, requested_by)
                VALUES (LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid), 'BLOCKED', ?)
                """, alice, eve, alice, eve, eve));

        actAs(alice);
        assertThat(personProfileService.profile(alice, eve)).isEmpty();
        assertThat(personProfileService.activities(alice, eve, PersonProfileService.ActivityScope.UPCOMING))
                .isEmpty();
        assertThat(personProfileService.profile(alice, UUID.randomUUID())).isEmpty();
    }

    @Test
    void aMuteIsOnlyTheCallersOwnSide() {
        UUID alice = newUser();
        UUID bob = newUser();
        friends(alice, bob);

        actAs(alice);
        socialGraphService.setMuted(alice, bob, true);

        actAs(alice);
        assertThat(socialGraphService.relationship(alice, bob).muted()).isTrue();
        assertThat(socialGraphService.getMutedIds(alice)).containsExactly(bob);
        actAs(bob);
        assertThat(socialGraphService.relationship(bob, alice).muted()).isFalse();
        assertThat(socialGraphService.getMutedIds(bob)).isEmpty();
    }

    @Test
    void onlyPlansThatHappenedCountTowardsTogetherAndStats() {
        UUID alice = newUser();
        UUID bob = newUser();
        friends(alice, bob);
        // Saturday 14 March 2026, 18:30 UTC: an evening plan the host started and ended.
        OffsetDateTime saturdayEvening = OffsetDateTime.of(2026, 3, 14, 18, 30, 0, 0, ZoneOffset.UTC);
        UUID happened = plan(alice, "Bouldering", saturdayEvening, true);
        UUID neverStarted = plan(alice, "Picnic", saturdayEvening.plusDays(1), false);
        joined(happened, bob);
        joined(neverStarted, bob);

        actAs(alice);
        assertThat(personProfileService.activities(alice, bob, PersonProfileService.ActivityScope.TOGETHER)
                .orElseThrow())
                .extracting(ActivityFeedItem::activityId)
                .containsExactly(happened);

        actAs(alice);
        UserStatsDto stats = userStatsService.stats(alice, 2026, ZoneOffset.UTC);
        assertThat(stats.totals().plans()).isEqualTo(1);
        assertThat(stats.totals().hosted()).isEqualTo(1);
        assertThat(stats.byMonth().get(2)).isEqualTo(new UserStatsDto.MonthCount(1, 0));
        assertThat(stats.byWeekday().get(5)).isEqualTo(1); // Monday first, so 5 is Saturday.
        assertThat(stats.byTimeOfDay()).isEqualTo(new UserStatsDto.TimeOfDay(0, 0, 1));
        assertThat(stats.byCategory().getFirst().category()).isEqualTo(ActivityCategory.CLIMBING);
        assertThat(stats.topCompanions()).extracting(companion -> companion.user().userId())
                .containsExactly(bob);

        actAs(bob);
        UserStatsDto bobs = userStatsService.stats(bob, 2026, ZoneOffset.UTC);
        assertThat(bobs.byMonth().get(2)).isEqualTo(new UserStatsDto.MonthCount(0, 1));
    }

    private void friends(UUID left, UUID right) {
        DatabaseRole.runAsSystem(() -> jdbcTemplate.update("""
                INSERT INTO friendships (user_a_id, user_b_id, status, requested_by, accepted_at)
                VALUES (LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid), 'ACCEPTED', ?, now())
                """, left, right, left, right, left));
    }

    private UUID group(UUID owner, UUID... members) {
        UUID id = UUID.randomUUID();
        DatabaseRole.runAsSystem(() -> {
            jdbcTemplate.update("INSERT INTO groups (group_id, owner_id, group_name) VALUES (?, ?, 'g')", id, owner);
            for (UUID member : members) {
                jdbcTemplate.update("INSERT INTO group_members (group_id, user_id) VALUES (?, ?)", id, member);
            }
        });
        return id;
    }

    /** A FRIENDS plan with its host's own JOINED row, as the create path writes it. */
    private UUID plan(UUID creator, String title, OffsetDateTime start, boolean startedAndEnded) {
        UUID id = UUID.randomUUID();
        DatabaseRole.runAsSystem(() -> {
            jdbcTemplate.update("""
                    INSERT INTO activities (activity_id, creator_id, activity_type, title, visibility,
                                            category, start_time, end_time, started_at, ended_at)
                    VALUES (?, ?, 'SPECIFIC_EVENT', ?, 'FRIENDS', 'CLIMBING', ?, ?, ?, ?)
                    """,
                    id, creator, title, start, start.plusHours(2),
                    startedAndEnded ? start : null,
                    startedAndEnded ? start.plusHours(2) : null);
            joined(id, creator);
        });
        return id;
    }

    private void joined(UUID activityId, UUID userId) {
        DatabaseRole.runAsSystem(() -> jdbcTemplate.update(
                "INSERT INTO participants (activity_id, user_id, status) VALUES (?, ?, 'JOINED')",
                activityId, userId));
    }
}
