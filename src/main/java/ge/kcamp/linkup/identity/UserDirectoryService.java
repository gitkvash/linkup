package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.repository.UserRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Looking users up by id or name - the {@code identity} module's read API.
 * <p>
 * Nothing like this existed, which is why the client had no way to show a name for
 * anyone: {@code GET /friends} returned bare UUIDs, and feed items and
 * notifications carried only a creator id. The UI printed raw 36-character UUIDs
 * as its primary content because there was no alternative.
 */
@Service
@Transactional(readOnly = true)
public class UserDirectoryService {

    /** Cap on a search or batch lookup, so one request can't ask for everything. */
    private static final int MAX_RESULTS = 50;

    /** Below this, a search would match most of the table. */
    private static final int MIN_QUERY_LENGTH = 2;

    private final UserRepository userRepository;

    public UserDirectoryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<UserSummary> findById(UUID userId) {
        return userRepository.findById(userId).map(UserDirectoryService::toSummary);
    }

    /**
     * Batch lookup, returned as a map so callers can decorate a list of ids without
     * an N+1. Ids that don't exist are simply absent.
     */
    public Map<UUID, UserSummary> findByIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> distinct = userIds.stream().limit(MAX_RESULTS).collect(Collectors.toSet());
        return userRepository.findAllById(distinct).stream()
                .map(UserDirectoryService::toSummary)
                .collect(Collectors.toMap(
                        UserSummary::userId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    /** Convenience for the common "id -> name or null" decoration. */
    public Map<UUID, String> usernamesFor(Collection<UUID> userIds) {
        return findByIds(userIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().username()));
    }

    /**
     * Username search. Returns nothing for a query shorter than
     * {@value #MIN_QUERY_LENGTH} characters rather than erroring - an empty result
     * is the honest answer while someone is still typing.
     */
    public List<UserSummary> search(String query, UUID excludeUserId) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        return userRepository
                .searchByUsername(trimmed, excludeUserId, Limit.of(MAX_RESULTS))
                .stream()
                .map(UserDirectoryService::toSummary)
                .toList();
    }

    private static UserSummary toSummary(ge.kcamp.linkup.identity.entity.User user) {
        return new UserSummary(user.getId(), user.getUsername());
    }
}
