package ge.kcamp.linkup.identity.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.identity.UserDirectoryService;
import ge.kcamp.linkup.identity.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserDirectoryService userDirectoryService;

    public UserController(UserDirectoryService userDirectoryService) {
        this.userDirectoryService = userDirectoryService;
    }

    /** Who am I - lets the client confirm its stored session against the server. */
    @GetMapping("/me")
    public ResponseEntity<UserSummary> me() {
        return userDirectoryService.findById(UserContext.getUserId())
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
