package ge.kcamp.linkup.identity.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.identity.ProfileService;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import ge.kcamp.linkup.identity.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserDirectoryService userDirectoryService;
    private final ProfileService profileService;

    public UserController(UserDirectoryService userDirectoryService, ProfileService profileService) {
        this.userDirectoryService = userDirectoryService;
        this.profileService = profileService;
    }

    /** Who am I - lets the client confirm its stored session against the server. */
    @GetMapping("/me")
    public ResponseEntity<UserSummary> me() {
        return userDirectoryService.findById(UserContext.getUserId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Change your own profile: the name people search for, the name they read, and a line
     * about yourself. There is no path to anyone else's - the id is the caller's, taken
     * from the token rather than the URL.
     */
    @PatchMapping("/me")
    public ResponseEntity<UserSummary> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return profileService.update(UserContext.getUserId(), request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Find someone to add. Any signed-in user can search: usernames are how people
     * identify each other here, and there is no other way to send a friend request
     * without already knowing a raw UUID.
     */
    @GetMapping("/search")
    public ResponseEntity<List<UserSummary>> search(
            @RequestParam @NotBlank @Size(max = 50) String q) {
        return ResponseEntity.ok(
                userDirectoryService.search(q, UserContext.getUserId()));
    }

    /**
     * Batch lookup for decorating a list of ids in one request - the friends list,
     * participants, notification actors.
     */
    @GetMapping
    public ResponseEntity<List<UserSummary>> byIds(@RequestParam List<UUID> ids) {
        return ResponseEntity.ok(
                List.copyOf(userDirectoryService.findByIds(ids).values()));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserSummary> byId(@PathVariable UUID userId) {
        return userDirectoryService.findById(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
