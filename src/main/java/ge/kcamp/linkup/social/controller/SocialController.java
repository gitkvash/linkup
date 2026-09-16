package ge.kcamp.linkup.social.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.social.FriendSummary;
import ge.kcamp.linkup.social.GroupMemberSummary;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.PendingFriendRequest;
import ge.kcamp.linkup.social.SocialGraphService;
import ge.kcamp.linkup.social.dto.CreateGroupRequest;
import ge.kcamp.linkup.social.dto.FriendRequestDto;
import ge.kcamp.linkup.social.dto.GroupMemberRequest;
import ge.kcamp.linkup.social.entity.Group;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class SocialController {

    private final SocialGraphService socialGraphService;
    private final GroupService groupService;

    public SocialController(SocialGraphService socialGraphService, GroupService groupService) {
        this.socialGraphService = socialGraphService;
        this.groupService = groupService;
    }

    @PostMapping("/api/v1/friends/request")
    public ResponseEntity<Void> sendRequest(@Valid @RequestBody FriendRequestDto request) {
        socialGraphService.sendFriendRequest(UserContext.getUserId(), request.targetUserId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/api/v1/friends/accept")
    public ResponseEntity<Void> acceptRequest(@Valid @RequestBody FriendRequestDto request) {
        socialGraphService.acceptFriendRequest(UserContext.getUserId(), request.targetUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/friends/decline")
    public ResponseEntity<Void> declineRequest(@Valid @RequestBody FriendRequestDto request) {
        socialGraphService.declineFriendRequest(UserContext.getUserId(), request.targetUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/friends/block")
    public ResponseEntity<Void> blockUser(@Valid @RequestBody FriendRequestDto request) {
        socialGraphService.blockUser(UserContext.getUserId(), request.targetUserId());
        return ResponseEntity.noContent().build();
    }

    /** Lifts a block the caller applied. Only they can; see SocialGraphService. */
    @PostMapping("/api/v1/friends/unblock")
    public ResponseEntity<Void> unblockUser(@Valid @RequestBody FriendRequestDto request) {
        socialGraphService.unblockUser(UserContext.getUserId(), request.targetUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * People the caller has blocked. Without this the client had no way to show a block,
     * so there was no route back to a user once blocked.
     */
    @GetMapping("/api/v1/friends/blocked")
    public ResponseEntity<List<FriendSummary>> blockedUsers() {
        return ResponseEntity.ok(socialGraphService.getBlockedUsers(UserContext.getUserId()));
    }

    /**
     * Now returns objects with usernames rather than bare UUIDs. This is a breaking
     * response-shape change, shipped together with the client that reads it.
     */
    @GetMapping("/api/v1/friends")
    public ResponseEntity<List<FriendSummary>> listFriends() {
        return ResponseEntity.ok(socialGraphService.getFriends(UserContext.getUserId()));
    }

    /** Unanswered requests, in both directions. */
    @GetMapping("/api/v1/friends/requests")
    public ResponseEntity<List<PendingFriendRequest>> pendingRequests() {
        return ResponseEntity.ok(socialGraphService.getPendingRequests(UserContext.getUserId()));
    }

    @DeleteMapping("/api/v1/friends/{userId}")
    public ResponseEntity<Void> unfriend(@PathVariable UUID userId) {
        socialGraphService.unfriend(UserContext.getUserId(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/groups")
    public ResponseEntity<Group> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        Group group = groupService.createGroup(UserContext.getUserId(), request.groupName());
        return ResponseEntity.status(HttpStatus.CREATED).body(group);
    }

    @PostMapping("/api/v1/groups/{groupId}/members")
    public ResponseEntity<Void> addMember(@PathVariable UUID groupId, @Valid @RequestBody GroupMemberRequest request) {
        groupService.addMember(UserContext.getUserId(), groupId, request.userId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/groups/{groupId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID groupId, @PathVariable UUID userId) {
        groupService.removeMember(UserContext.getUserId(), groupId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/groups/mine")
    public ResponseEntity<List<Group>> myGroups() {
        return ResponseEntity.ok(groupService.listMyGroups(UserContext.getUserId()));
    }

    @GetMapping("/api/v1/groups/{groupId}")
    public ResponseEntity<Group> getGroup(@PathVariable UUID groupId) {
        return ResponseEntity.ok(groupService.getGroup(UserContext.getUserId(), groupId));
    }

    @GetMapping("/api/v1/groups/{groupId}/members")
    public ResponseEntity<List<GroupMemberSummary>> groupMembers(@PathVariable UUID groupId) {
        return ResponseEntity.ok(groupService.listMembers(UserContext.getUserId(), groupId));
    }
}
