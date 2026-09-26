package ge.kcamp.linkup.activity.controller;

import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.ActivityParticipant;
import ge.kcamp.linkup.activity.ActivityParticipationService;
import ge.kcamp.linkup.activity.ActivityLifecycleService;
import ge.kcamp.linkup.activity.ActivityQueryService;
import ge.kcamp.linkup.activity.command.ActivityCommandHandler;
import ge.kcamp.linkup.activity.command.CreateStructuredActivityCommand;
import ge.kcamp.linkup.activity.command.DeleteActivityCommand;
import ge.kcamp.linkup.activity.command.UpdateActivityCommand;
import ge.kcamp.linkup.activity.dto.CreateStructuredActivityRequest;
import ge.kcamp.linkup.activity.dto.InviteRequest;
import ge.kcamp.linkup.activity.dto.ParticipationDto;
import ge.kcamp.linkup.activity.dto.UpdateActivityRequest;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.UserContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {

    private final ActivityCommandHandler activityCommandHandler;
    private final ActivityQueryService activityQueryService;
    private final ActivityParticipationService participationService;
    private final ActivityLifecycleService activityLifecycleService;

    public ActivityController(
            ActivityCommandHandler activityCommandHandler,
            ActivityQueryService activityQueryService,
            ActivityParticipationService participationService,
            ActivityLifecycleService activityLifecycleService) {
        this.activityCommandHandler = activityCommandHandler;
        this.activityQueryService = activityQueryService;
        this.participationService = participationService;
        this.activityLifecycleService = activityLifecycleService;
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

    /**
     * Delete a plan for everyone. Creator only, same 404 rule as {@link #update}.
     * {@link #cancel} is the one that keeps it visible.
     */
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

    /**
     * Invite more friends to a plan that already exists. Host only; answers with the
     * participant list so the detail screen can show who was added without a second GET.
     */
    @PostMapping("/{id}/invites")
    public ResponseEntity<List<ActivityParticipant>> invite(
            @PathVariable UUID id, @Valid @RequestBody InviteRequest request) {
        return ResponseEntity.ok(
                participationService.invite(id, UserContext.getUserId(), request.userIds()));
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

    /**
     * The host says it has begun - the only way a plan goes live. One nobody starts is
     * cancelled two hours after its start time (see {@code ActivityStatusResolver}).
     * <p>
     * Answers with the read model, so the client can render the new status without a
     * follow-up GET. Creator only; everyone else gets the 404 that editing gives.
     * <p>
     * One transaction for the write and the read that answers it, here and in
     * {@link #end} and {@link #cancel}: one connection borrowed, stamped and committed
     * instead of two. The lifecycle events still go out after it commits.
     */
    @Transactional
    @PostMapping("/{id}/start")
    public ResponseEntity<ActivityFeedItem> start(@PathVariable UUID id) {
        UUID actorId = UserContext.getUserId();
        activityLifecycleService.start(id, actorId);
        return activityQueryService.findById(id, actorId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The host says it is over, before its window would have ended it. */
    @Transactional
    @PostMapping("/{id}/end")
    public ResponseEntity<ActivityFeedItem> end(@PathVariable UUID id) {
        UUID actorId = UserContext.getUserId();
        activityLifecycleService.end(id, actorId);
        return activityQueryService.findById(id, actorId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The host calls it off. Unlike {@link #delete} the plan stays, reading as cancelled,
     * so the people in it still see what happened to it. 400 once it has already
     * happened; creator only, same 404 rule as {@link #update}.
     */
    @Transactional
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ActivityFeedItem> cancel(@PathVariable UUID id) {
        UUID actorId = UserContext.getUserId();
        activityLifecycleService.cancel(id, actorId);
        return activityQueryService.findById(id, actorId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
