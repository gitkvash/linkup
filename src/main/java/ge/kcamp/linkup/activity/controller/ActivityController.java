package ge.kcamp.linkup.activity.controller;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityParticipant;
import ge.kcamp.linkup.activity.ActivityParticipationService;
import ge.kcamp.linkup.activity.ActivityQueryService;
import ge.kcamp.linkup.activity.command.ActivityCommandHandler;
import ge.kcamp.linkup.activity.command.CreateActivityFromTextCommand;
import ge.kcamp.linkup.activity.command.CreateStructuredActivityCommand;
import ge.kcamp.linkup.activity.command.DeleteActivityCommand;
import ge.kcamp.linkup.activity.command.UpdateActivityCommand;
import ge.kcamp.linkup.activity.dto.CreateActivityFromTextRequest;
import ge.kcamp.linkup.activity.dto.CreateStructuredActivityRequest;
import ge.kcamp.linkup.activity.dto.ParticipationDto;
import ge.kcamp.linkup.activity.dto.UpdateActivityRequest;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.web.RequestZone;
import ge.kcamp.linkup.UserContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {

    private final ActivityCommandHandler activityCommandHandler;
    private final ActivityQueryService activityQueryService;
    private final ActivityParticipationService participationService;

    public ActivityController(
            ActivityCommandHandler activityCommandHandler,
            ActivityQueryService activityQueryService,
            ActivityParticipationService participationService) {
        this.activityCommandHandler = activityCommandHandler;
        this.activityQueryService = activityQueryService;
        this.participationService = participationService;
    }

    @PostMapping("/from-text")
    public ResponseEntity<Activity> createFromText(@Valid @RequestBody CreateActivityFromTextRequest request) {
        UUID creatorId = UserContext.getUserId();
        List<UUID> invitees = request.inviteeUserIds() == null ? List.of() : request.inviteeUserIds();
        Activity activity = activityCommandHandler.handle(new CreateActivityFromTextCommand(
                creatorId, request.rawText(), request.visibility(), request.groupId(), invitees,
                RequestZone.resolve(request.timeZone())));
        return ResponseEntity.status(HttpStatus.CREATED).body(activity);
    }

    @PostMapping
    public ResponseEntity<Activity> createStructured(@Valid @RequestBody CreateStructuredActivityRequest request) {
        UUID creatorId = UserContext.getUserId();
        List<UUID> invitees = request.inviteeUserIds() == null ? List.of() : request.inviteeUserIds();
        Activity activity = activityCommandHandler.handle(new CreateStructuredActivityCommand(
                creatorId, request.title(), request.startTime(), request.endTime(), request.resolvedHasTime(),
                request.lat(), request.lng(), request.addressText(), request.visibility(), request.groupId(),
                invitees, request.category(), request.repeatFrequency(),
                request.resolvedRepeatInterval(), request.resolvedRepeatUntil()));
        return ResponseEntity.status(HttpStatus.CREATED).body(activity);
    }

    /**
     * 404 covers both "no such activity" and "not yours to see" - deliberately
     * indistinguishable, so this can't be used to probe which ids exist.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ActivityFeedItem> getById(@PathVariable UUID id) {
        return activityQueryService.findById(id, UserContext.getUserId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Edit a plan. Only the creator may; everyone else gets the same 404 that
     * {@code GET /activities/{id}} gives for something they cannot see.
     * <p>
     * Answers with the updated read model rather than the entity, so the client can put
     * the detail screen straight back on screen without a follow-up GET - the entity
     * carries neither the participant count nor the viewer's own status.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<ActivityFeedItem> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateActivityRequest request) {

        UUID actorId = UserContext.getUserId();
        activityCommandHandler.handle(new UpdateActivityCommand(
                id, actorId, request.title(), request.startTime(), request.endTime(),
                request.resolvedHasTime(), request.lat(), request.lng(), request.addressText(),
                request.visibility(), request.groupId(), request.category(),
                request.repeatFrequency(), request.resolvedRepeatInterval(),
                request.resolvedRepeatUntil()));

        return activityQueryService.findById(id, actorId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Cancel a plan. Creator only, same 404 rule as {@link #update}. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        activityCommandHandler.handle(new DeleteActivityCommand(id, UserContext.getUserId()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mine")
    public ResponseEntity<List<ActivityFeedItem>> getMine() {
        UUID creatorId = UserContext.getUserId();
        return ResponseEntity.ok(activityQueryService.findByCreator(creatorId));
    }

    /** Invitations the caller hasn't answered yet. */
    @GetMapping("/invited")
    public ResponseEntity<List<ActivityFeedItem>> getInvitations() {
        return ResponseEntity.ok(
                participationService.pendingInvitations(UserContext.getUserId()));
    }

    /** Activities the caller has joined but didn't create - see {@code /mine} for those. */
    @GetMapping("/joined")
    public ResponseEntity<List<ActivityFeedItem>> getJoined() {
        return ResponseEntity.ok(
                participationService.joinedActivities(UserContext.getUserId()));
    }

    @GetMapping("/{id}/participants")
    public ResponseEntity<List<ActivityParticipant>> getParticipants(@PathVariable UUID id) {
        return ResponseEntity.ok(
                participationService.listParticipants(id, UserContext.getUserId()));
    }

    /** Idempotent: joining something you're already in is a no-op, not an error. */
    @PostMapping("/{id}/join")
    public ResponseEntity<ParticipationDto> join(@PathVariable UUID id) {
        return ResponseEntity.ok(new ParticipationDto(
                participationService.join(id, UserContext.getUserId())));
    }

    @DeleteMapping("/{id}/join")
    public ResponseEntity<Void> leave(@PathVariable UUID id) {
        participationService.leave(id, UserContext.getUserId());
        return ResponseEntity.noContent().build();
    }

    /** Answer an invitation: {@code going=true} accepts, {@code false} declines. */
    @PostMapping("/{id}/respond")
    public ResponseEntity<ParticipationDto> respond(
            @PathVariable UUID id, @RequestParam boolean going) {
        return ResponseEntity.ok(new ParticipationDto(
                participationService.respond(id, UserContext.getUserId(), going)));
    }
}
