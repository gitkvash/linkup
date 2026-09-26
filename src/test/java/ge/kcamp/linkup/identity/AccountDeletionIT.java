package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.DatabaseRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Account deletion against the real schema and the real roles: that V33's function runs
 * as the restricted app role, reaches every table that references the account, deletes
 * only the caller, and leaves other people's plans in a state the visibility rules still
 * accept.
 */
@SpringBootTest
class AccountDeletionIT extends AbstractIntegrationTest {

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deletesTheAccountAndEverythingThatBelongsToIt() {
        UUID alice = newUser();
        UUID bob = newUser();

        UUID aliceGroup = group(alice, alice, bob);
        UUID bobsGroup = group(bob, bob, alice);
        UUID alicePlan = plan(alice, "PUBLIC", null);
        UUID bobsPlan = plan(bob, "FRIENDS", null);
        UUID bobsPlanForAlicesGroup = plan(bob, "GROUP", aliceGroup);
        UUID bobsPlanForHisGroup = plan(bob, "GROUP", bobsGroup);
        system(() -> {
            jdbcTemplate.update("INSERT INTO participants (activity_id, user_id, status) VALUES (?, ?, 'JOINED')",
                    alicePlan, bob);
            jdbcTemplate.update("INSERT INTO participants (activity_id, user_id, status) VALUES (?, ?, 'JOINED')",
                    bobsPlan, alice);
            jdbcTemplate.update("""
                    INSERT INTO friendships (user_a_id, user_b_id, status, requested_by)
                    VALUES (LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid), 'ACCEPTED', ?)
                    """, alice, bob, alice, bob, alice);
            jdbcTemplate.update("""
                    INSERT INTO notifications (notification_id, recipient_user_id, type, title)
                    VALUES (?, ?, 'FRIEND_REQUEST', 'x'), (?, ?, 'FRIEND_REQUEST', 'x')
                    """, UUID.randomUUID(), alice, UUID.randomUUID(), bob);
            jdbcTemplate.update("INSERT INTO device_tokens (user_id, fcm_token, platform) VALUES (?, ?, 'ANDROID')",
                    alice, "tok-" + alice);
            jdbcTemplate.update("""
                    INSERT INTO refresh_tokens (id, user_id, issued_at, expires_at)
                    VALUES (?, ?, now(), now() + interval '1 day')
                    """, UUID.randomUUID(), alice);
            return null;
        });

        actAs(alice);
        assertThat(accountDeletionService.deleteOwnAccount(alice)).isTrue();

        assertThat(count("SELECT count(*) FROM users WHERE user_id = ?", alice)).isZero();
        assertThat(count("SELECT count(*) FROM activities WHERE creator_id = ?", alice)).isZero();
        assertThat(count("SELECT count(*) FROM groups WHERE owner_id = ?", alice)).isZero();
        assertThat(count("SELECT count(*) FROM group_members WHERE user_id = ?", alice)).isZero();
        assertThat(count("SELECT count(*) FROM participants WHERE user_id = ?", alice)).isZero();
        assertThat(count("SELECT count(*) FROM friendships WHERE user_a_id = ? OR user_b_id = ?", alice, alice))
                .isZero();
        assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", alice)).isZero();
        assertThat(count("SELECT count(*) FROM device_tokens WHERE user_id = ?", alice)).isZero();
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", alice)).isZero();

        // Bob keeps his account and his plans. The one he shared with Alice's group - which
        // went with her - is his and his participants' now, rather than gone.
        assertThat(count("SELECT count(*) FROM users WHERE user_id = ?", bob)).isOne();
        assertThat(count("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", bob)).isOne();
        assertThat(system(() -> jdbcTemplate.queryForList(
                "SELECT visibility FROM activities WHERE activity_id = ? AND group_id IS NULL",
                String.class, bobsPlanForAlicesGroup)))
                .containsExactly("PRIVATE");
        // His own group stays, without her in it, and so does the plan made for it.
        assertThat(count("SELECT count(*) FROM group_members WHERE group_id = ?", bobsGroup)).isOne();
        assertThat(count("SELECT count(*) FROM activities WHERE activity_id = ? AND group_id = ?",
                bobsPlanForHisGroup, bobsGroup)).isOne();
    }

    @Test
    void aSecondDeleteFindsNothingToDelete() {
        UUID alice = newUser();

        actAs(alice);
        assertThat(accountDeletionService.deleteOwnAccount(alice)).isTrue();
        actAs(alice);
        assertThat(accountDeletionService.deleteOwnAccount(alice)).isFalse();
    }

    @Test
    void onlyEverDeletesTheCaller() {
        UUID alice = newUser();
        UUID mallory = newUser();

        actAs(mallory);
        assertThatThrownBy(() -> accountDeletionService.deleteOwnAccount(alice))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("SELECT count(*) FROM users WHERE user_id IN (?, ?)", alice, mallory)).isEqualTo(2);
    }

    /**
     * V33 names every table that references {@code users}. A new one would make every
     * deletion of an account with a row in it fail on the foreign key - which is the point
     * of leaving the keys NO ACTION, but only if someone finds out before a user does.
     * Add the table to {@code app_delete_current_account()}, then here.
     */
    @Test
    void everyTableThatReferencesUsersIsOneTheDeleteKnowsAbout() {
        List<String> referencing = system(() -> jdbcTemplate.queryForList("""
                SELECT DISTINCT c.conrelid::regclass::text
                FROM pg_constraint c
                WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass
                ORDER BY 1
                """, String.class));

        assertThat(referencing).containsExactlyInAnyOrder(
                "activities", "device_tokens", "friendships", "group_members", "groups",
                "notifications", "participants", "refresh_tokens");
    }

    private UUID group(UUID owner, UUID... members) {
        UUID id = UUID.randomUUID();
        system(() -> {
            jdbcTemplate.update("INSERT INTO groups (group_id, owner_id, group_name) VALUES (?, ?, 'g')", id, owner);
            for (UUID member : members) {
                jdbcTemplate.update("INSERT INTO group_members (group_id, user_id) VALUES (?, ?)", id, member);
            }
            return null;
        });
        return id;
    }

    private UUID plan(UUID creator, String visibility, UUID groupId) {
        UUID id = UUID.randomUUID();
        system(() -> jdbcTemplate.update("""
                INSERT INTO activities (activity_id, creator_id, activity_type, title, visibility, group_id, start_time)
                VALUES (?, ?, 'SPECIFIC_EVENT', 'plan', ?, ?, now() + interval '1 day')
                """, id, creator, visibility, groupId));
        return id;
    }

    private long count(String sql, Object... args) {
        return system(() -> jdbcTemplate.queryForObject(sql, Long.class, args));
    }

    /** As the owner, so assertions see every row rather than what the last caller may. */
    private static <T> T system(Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        DatabaseRole.runAsSystem(() -> result.set(work.get()));
        return result.get();
    }
}
