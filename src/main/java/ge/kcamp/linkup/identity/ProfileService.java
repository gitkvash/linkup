package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.dto.EmailResponse;
import ge.kcamp.linkup.identity.dto.UpdateProfileRequest;
import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.exception.DuplicateEmailException;
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

    /** The caller's own reset address. Empty only if the account no longer exists. */
    @Transactional(readOnly = true)
    public Optional<EmailResponse> findEmail(UUID userId) {
        return userRepository.findById(userId).map(user -> new EmailResponse(user.getEmail()));
    }

    /**
     * Sets the address reset codes are sent to. Kept out of {@link #update} and out of
     * {@link UserSummary} on purpose: an email is private to its owner, and the profile
     * shape is what every other user reads.
     *
     * @return the stored (normalised) address, or empty if the account no longer exists
     * @throws DuplicateEmailException if another account already uses it
     */
    @Transactional
    public Optional<EmailResponse> updateEmail(UUID userId, String email) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        User user = found.get();
        String normalized = EmailAddress.normalize(email);
        if (normalized != null && !normalized.equals(user.getEmail()) && userRepository.existsByEmail(normalized)) {
            throw new DuplicateEmailException();
        }
        user.setEmail(normalized);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEmailException();
        }
        return Optional.of(new EmailResponse(normalized));
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
