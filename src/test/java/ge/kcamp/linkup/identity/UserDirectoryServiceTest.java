package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}
