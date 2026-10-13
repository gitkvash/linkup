package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.dto.TermsStatusResponse;
import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Agreement to the Terms and Privacy Policy for accounts that already exist: the ones made
 * before they were introduced, and everyone again when the documents are revised. New
 * accounts record theirs as they are created ({@link IdentityService}).
 */
@Service
public class TermsService {

    private final UserRepository userRepository;

    public TermsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<TermsStatusResponse> status(UUID userId) {
        return userRepository.findById(userId).map(user ->
                new TermsStatusResponse(LegalDocuments.CURRENT_VERSION, user.getTermsVersion()));
    }

    /**
     * Records that the caller agreed to {@code version}, which must be the current one.
     * Empty if the account no longer exists.
     */
    @Transactional
    public Optional<TermsStatusResponse> accept(UUID userId, String version) {
        LegalDocuments.requireCurrent(version);
        return userRepository.findById(userId).map(user -> {
            if (!version.equals(user.getTermsVersion())) {
                user.setTermsVersion(version);
                user.setTermsAcceptedAt(ZonedDateTime.now());
            }
            return new TermsStatusResponse(LegalDocuments.CURRENT_VERSION, user.getTermsVersion());
        });
    }
}
