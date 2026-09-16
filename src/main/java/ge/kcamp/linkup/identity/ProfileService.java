package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.dto.UpdateProfileRequest;
import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.exception.DuplicateUsernameException;
import ge.kcamp.linkup.identity.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Editing your own profile - the write half of {@link UserDirectoryService}.
 * <p>
 * A username change is a real rename, not an alias: the old one is free again the moment
 * it is released, and everything that shows a name reads it live from {@code users}, so
 * nothing needs rewriting. The token keeps the old spelling until it is reissued, which
 * is harmless - authorisation reads the id from it, never the name.
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;

    public ProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public Optional<UserSummary> update(UUID userId, UpdateProfileRequest request) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        User user = found.get();

        String username = UpdateProfileRequest.blankToNull(request.username());
        if (username != null && !username.equalsIgnoreCase(user.getUsername())) {
            // Checked before the write for the message's sake, and caught after it for
            // correctness - two people can claim the same free name at once.
            if (userRepository.existsByUsernameIgnoringCase(username)) {
                throw new DuplicateUsernameException();
            }
            user.setUsername(username);
        } else if (username != null) {
            // Same name, different spelling: allowed, since the uniqueness rule is
            // case-insensitive and what gets shown is what was typed.
            user.setUsername(username);
        }

        user.setDisplayName(UpdateProfileRequest.blankToNull(request.displayName()));
        user.setBio(UpdateProfileRequest.blankToNull(request.bio()));

        try {
            User saved = userRepository.saveAndFlush(user);
            return Optional.of(new UserSummary(
                    saved.getId(), saved.getUsername(), saved.getDisplayName(), saved.getBio()));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUsernameException();
        }
    }
}
