package ge.kcamp.linkup.identity;

import ge.kcamp.linkup.identity.dto.AuthResponse;
import ge.kcamp.linkup.identity.entity.User;
import ge.kcamp.linkup.identity.exception.AuthenticationFailedException;
import ge.kcamp.linkup.identity.exception.DuplicateUsernameException;
import ge.kcamp.linkup.identity.repository.UserRepository;
import ge.kcamp.linkup.identity.security.GoogleTokenVerifier;
import ge.kcamp.linkup.identity.security.JwtUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final GoogleTokenVerifier googleTokenVerifier;

    public IdentityService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            GoogleTokenVerifier googleTokenVerifier) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.googleTokenVerifier = googleTokenVerifier;
    }

    @Transactional
    public AuthResponse register(String username, String rawPassword) {
        // Case-insensitively, so "Alice" cannot be registered alongside "alice" - the
        // spelling the user typed is still what gets stored and shown.
        if (userRepository.existsByUsernameIgnoringCase(username)) {
            throw new DuplicateUsernameException();
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));

        User saved;
        try {
            // saveAndFlush, not save: the unique-constraint violation has to surface
            // here rather than at commit, or two simultaneous registrations of the
            // same username escape this method and become a generic 500.
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUsernameException();
        }

        return issueToken(saved);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String username, String rawPassword) {
        User user = userRepository.findByUsernameIgnoringCase(username)
                .orElseThrow(AuthenticationFailedException::new);

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new AuthenticationFailedException();
        }

        return issueToken(user);
    }

    @Transactional
    public AuthResponse loginWithGoogle(String idToken) {
        GoogleTokenVerifier.Result verified = googleTokenVerifier.verify(idToken);
        User user = userRepository.findByGoogleId(verified.subject())
                .orElseGet(() -> createGoogleUser(verified));
        return issueToken(user);
    }

    private User createGoogleUser(GoogleTokenVerifier.Result verified) {
        User user = new User();
        user.setGoogleId(verified.subject());
        user.setUsername(generateUniqueUsername(verified.email()));
        // passwordHash is left null - a Google-only account authenticates by
        // googleId, never by password. V21 dropped the NOT NULL constraint for
        // exactly this row shape.
        return userRepository.saveAndFlush(user);
    }

    /**
     * Derives a username from the email's local part, since Google sign-in never asks
     * the user to pick one. Falls back to "user" if that leaves nothing usable, then
     * appends a numeric suffix until it's unique - the same guarantee
     * {@link #register} gets from the caller having already picked an available name.
     */
    private String generateUniqueUsername(String email) {
        String base = email == null ? "" : email.substring(0, email.indexOf('@') >= 0 ? email.indexOf('@') : email.length());
        base = base.toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (base.isEmpty()) {
            base = "user";
        }
        if (base.length() > 46) {
            base = base.substring(0, 46);
        }

        if (!userRepository.existsByUsernameIgnoringCase(base)) {
            return base;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            String candidate = base + suffix;
            if (!userRepository.existsByUsernameIgnoringCase(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique username for " + base);
    }

    private AuthResponse issueToken(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return new AuthResponse(token, user.getId(), user.getUsername());
    }
}
