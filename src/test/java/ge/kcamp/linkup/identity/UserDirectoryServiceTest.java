package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDirectoryServiceTest {

    @Test
    void likeWildcardsAreEscapedSoTheyMatchLiterally() {
        assertThat(UserDirectoryService.escapeLikeWildcards("__")).isEqualTo("!_!_");
        assertThat(UserDirectoryService.escapeLikeWildcards("%%")).isEqualTo("!%!%");
        // The escape character first, or the escapes added for % and _ would be doubled.
        assertThat(UserDirectoryService.escapeLikeWildcards("a!_b")).isEqualTo("a!!!_b");
        assertThat(UserDirectoryService.escapeLikeWildcards("nino.k-1")).isEqualTo("nino.k-1");
    }

    @Test
    void searchPassesTheEscapedQueryToTheRepository() {
        UserRepository users = mock(UserRepository.class);
        when(users.searchByUsername(any(), any(), any(Limit.class))).thenReturn(List.of());
        UUID caller = UUID.randomUUID();

        new UserDirectoryService(users).search(" n_% ", caller);

        verify(users).searchByUsername(eq("n!_!%"), eq(caller), any(Limit.class));
    }

    /**
     * A friends list or a feed page asks for far more than 50 people at once. The lookup
     * used to stop at 50 - before de-duplicating - and everyone past that came back nameless.
     */
    @Test
    void aBatchLookupFindsEveryoneHoweverManyInBoundedQueries() {
        UserRepository users = mock(UserRepository.class);
        when(users.findAllById(any())).thenAnswer(call -> {
            List<User> found = new ArrayList<>();
            for (UUID id : call.<Iterable<UUID>>getArgument(0)) {
                User user = new User();
                user.setId(id);
                user.setUsername("u" + id);
                found.add(user);
            }
            return found;
        });
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1_200; i++) {
            ids.add(UUID.randomUUID());
        }
        ids.add(ids.get(0));

        Map<UUID, UserSummary> found = new UserDirectoryService(users).findByIds(ids);

        assertThat(found).hasSize(1_200).containsKeys(ids.get(0), ids.get(1_199));
        ArgumentCaptor<Iterable<UUID>> chunks = ArgumentCaptor.captor();
        verify(users, times(3)).findAllById(chunks.capture());
        assertThat(chunks.getAllValues()).allSatisfy(chunk -> assertThat(chunk).hasSizeLessThanOrEqualTo(500));
    }
}
