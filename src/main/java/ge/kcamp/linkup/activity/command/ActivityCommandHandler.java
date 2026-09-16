package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.activity.ActivityFactory;
import ge.kcamp.linkup.activity.CasualPlanSpec;
import ge.kcamp.linkup.activity.StructuredEventSpec;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Location;
import ge.kcamp.linkup.activity.entity.Participant;
import ge.kcamp.linkup.activity.entity.ParticipantId;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.exception.InvalidGroupException;
import ge.kcamp.linkup.activity.exception.InvalidInviteeException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.LocationRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import ge.kcamp.linkup.nlp.NlpParserService;
import ge.kcamp.linkup.nlp.ParsedActivityText;
import ge.kcamp.linkup.social.GroupService;
import ge.kcamp.linkup.social.SocialGraphService;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ActivityCommandHandler {

    private static final int WGS84_SRID = 4326;

    /** {@code activities.title VARCHAR(255)}. */
    private static final int MAX_TITLE_LENGTH = 255;

    /** {@code locations.address_text VARCHAR(255)}. */
    private static final int MAX_ADDRESS_LENGTH = 255;

    private static final char ELLIPSIS = '…';

    private final ActivityRepository activityRepository;
    private final LocationRepository locationRepository;
    private final ParticipantRepository participantRepository;
    private final ActivityFactory activityFactory;
    private final NlpParserService nlpParserService;
    private final SocialGraphService socialGraphService;
    private final GroupService groupService;
    private final ApplicationEventPublisher eventPublisher;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), WGS84_SRID);

    public ActivityCommandHandler(
            ActivityRepository activityRepository,
            LocationRepository locationRepository,
            ParticipantRepository participantRepository,
            ActivityFactory activityFactory,
            NlpParserService nlpParserService,
            SocialGraphService socialGraphService,
            GroupService groupService,
            ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.locationRepository = locationRepository;
        this.participantRepository = participantRepository;
        this.activityFactory = activityFactory;
        this.nlpParserService = nlpParserService;
        this.socialGraphService = socialGraphService;
        this.groupService = groupService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Activity handle(CreateActivityFromTextCommand command) {
        ZoneId zone = command.zone();
        ParsedActivityText parsed = nlpParserService.parse(command.rawText(), zone);
        // Still no geocoding service, so a text-only location stays text - but it is now
        // persisted as the activity's address instead of being discarded, which is what
        // made every text-created plan show up with no place at all.
        ZonedDateTime startTime = parsed.startTime()
                .orElseGet(() -> ZonedDateTime.now(zone).plusHours(1));
        // No day/time found at all: startTime above is a made-up "in an hour" slot, which
        // is still a specific time worth showing. Only a day-only match (e.g. "tomorrow",
        // defaulted to a guessed evening hour) should hide the clock.
        boolean hasTime = parsed.startTime().isEmpty() || parsed.hasExplicitTime();

        // Both are cut to their column widths. The title is whatever is left of the
        // user's own sentence once the time and place are stripped, and the client lets
        // them type 280 characters, so this is reachable by typing a long plan out.
        // Truncating is the right answer where rejecting is not: the input was valid, it
        // just doesn't fit a column - and it used to reach Postgres and come back as a
        // 409 telling the user their plan conflicted with something. The structured
        // endpoints, where the title is a field typed on its own, validate its length
        // instead (see CreateStructuredActivityRequest).
        String locationText = truncate(parsed.locationText().orElse(null), MAX_ADDRESS_LENGTH);
        CasualPlanSpec spec =
                new CasualPlanSpec(truncateTitle(parsed.title()), startTime, hasTime, locationText);

        return createAndPersist(spec, command.creatorId(), command.visibility(), command.groupId(),
                null, null, locationText, command.inviteeUserIds(), null);
    }

    @Transactional
    public Activity handle(CreateStructuredActivityCommand command) {
        StructuredEventSpec spec = new StructuredEventSpec(
                command.title(), command.startTime(), command.endTime(), command.hasTime(),
                command.addressText(), command.lat(), command.lng(),
                command.repeatFrequency(), command.repeatInterval(), command.repeatUntil());

        return createAndPersist(spec, command.creatorId(), command.visibility(), command.groupId(),
                command.lat(), command.lng(), command.addressText(), command.inviteeUserIds(),
                command.category());
    }

    /**
     * Applies an edit to an existing plan.
     * <p>
     * The whole editable surface is replaced, matching what the client's edit form
     * sends. Participants are untouched: who is coming is changed through join/respond,
     * and re-deriving it here would resurrect invitations people had already declined.
     * <p>
     * No event is published. {@link ActivityCreatedEvent} means "a new plan exists" -
     * feed fan-out and an ACTIVITY_INVITE notification both follow from it, and neither
     * is true of an edit. One consequence to know about: a timeline entry keeps the
     * Redis score it was written with, so moving a plan's start time re-sorts it in the
     * feed (the score is recomputed on read) without moving it in the stored timeline.
     */
    @Transactional
    public Activity handle(UpdateActivityCommand command) {
        Activity activity = requireOwned(command.activityId(), command.actorId());

        UUID audienceGroupId = resolveGroupId(
                command.actorId(), command.visibility(), command.groupId());

        activity.setTitle(command.title());
        activity.setStartTime(command.startTime());
        activity.setEndTime(command.endTime());
        activity.setHasTime(command.hasTime());
        activity.setVisibility(command.visibility());
        activity.setGroupId(audienceGroupId);
        // Full replacement, like the rest of this command: a repeat rule the edit form
        // didn't send is a rule the user turned off, and leaving it in place would make
        // "make this a one-off" the one change the form couldn't express. The interval
        // and end date follow the frequency so the row can't violate
        // chk_activities_repeat.
        activity.setRepeatFrequency(command.repeatFrequency());
        activity.setRepeatInterval(
                command.repeatFrequency() == null ? null : command.repeatInterval());
        activity.setRepeatUntil(
                command.repeatFrequency() == null ? null : command.repeatUntil());
        if (command.category() != null) {
            activity.setCategory(command.category());
        }

        boolean hasCoordinates = command.lat() != null && command.lng() != null;
        // A plan edited through the structured form has a real time and place, which is
        // what separates a SPECIFIC_EVENT from a CASUAL_PLAN (see ActivityFactory). An
        // edit that carries no coordinates leaves the type as it was.
        if (hasCoordinates) {
            activity.setActivityType(ge.kcamp.linkup.activity.enums.ActivityType.SPECIFIC_EVENT);
        }

        Activity saved = activityRepository.save(activity);
        applyLocation(saved, command.lat(), command.lng(), command.addressText());
        return saved;
    }

    /**
     * Cancels a plan. {@code participants} and {@code locations} go with it through
     * {@code ON DELETE CASCADE} (V14). Feed timelines are not swept: they hold ids, and
     * the feed resolves those through the query service, so a deleted plan simply stops
     * appearing.
     */
    @Transactional
    public void handle(DeleteActivityCommand command) {
        Activity activity = requireOwned(command.activityId(), command.actorId());
        activityRepository.delete(activity);
    }

    /**
     * Loads a plan for editing, or fails as if it did not exist.
     * <p>
     * A non-creator gets {@link ActivityNotVisibleException} - the same 404 an unknown
     * id gets - rather than a 403, because a 403 would confirm the id is real to anyone
     * who guessed it. This matches {@code GET /activities/{id}}.
     */
    private Activity requireOwned(UUID activityId, UUID actorId) {
        return activityRepository.findById(activityId)
                .filter(activity -> activity.getCreatorId().equals(actorId))
                .orElseThrow(ActivityNotVisibleException::new);
    }

    /**
     * Writes the plan's place: updates the existing row, creates one if the plan had
     * none, and removes it when the edit cleared both the coordinates and the address.
     * Same rule as creation - a row exists if there are coordinates <em>or</em> a name.
     */
    private void applyLocation(Activity activity, Double lat, Double lng, String addressText) {
        boolean hasCoordinates = lat != null && lng != null;
        boolean hasAddress = addressText != null && !addressText.isBlank();

        Location existing = locationRepository.findByActivityId(activity.getId()).orElse(null);

        if (!hasCoordinates && !hasAddress) {
            if (existing != null) {
                locationRepository.delete(existing);
            }
            return;
        }

        Point point = hasCoordinates
                ? geometryFactory.createPoint(new Coordinate(lng, lat))
                : null;

        Location location = existing == null
                ? Location.builder().activity(activity).build()
                : existing;
        location.setGeomPoint(point);
        location.setAddressText(addressText);
        locationRepository.save(location);
    }

    /** Trims a derived title to the column width, marking that it was cut. */
    private static String truncateTitle(String title) {
        if (title == null || title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        return truncate(title, MAX_TITLE_LENGTH - 1).stripTrailing() + ELLIPSIS;
    }

    /**
     * Cuts to at most {@code max} characters without splitting a surrogate pair - an
     * emoji landing on the boundary would otherwise become half a character, which
     * Postgres rejects as invalid UTF-8.
     */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }

    private Activity createAndPersist(
            ge.kcamp.linkup.activity.ActivitySpec spec,
            UUID creatorId,
            ge.kcamp.linkup.activity.enums.ActivityVisibility visibility,
            UUID groupId,
            Double lat,
            Double lng,
            String addressText,
            List<UUID> inviteeUserIds,
            ge.kcamp.linkup.activity.enums.ActivityCategory category) {

        List<UUID> invitees = inviteeUserIds == null ? List.of() : inviteeUserIds;
        requireFriends(creatorId, invitees);

        UUID audienceGroupId = resolveGroupId(creatorId, visibility, groupId);
        Activity activity = activityFactory.createFrom(spec, creatorId, visibility, audienceGroupId, category);
        Activity saved = activityRepository.save(activity);

        // A row is written when there are coordinates OR just an address, so a text-created
        // plan keeps its place name. Coordinates stay null in that case, and every reader
        // has to cope with that (see Location.geomPoint).
        boolean hasCoordinates = lat != null && lng != null;
        boolean hasAddress = addressText != null && !addressText.isBlank();
        if (hasCoordinates || hasAddress) {
            Point point = hasCoordinates
                    ? geometryFactory.createPoint(new Coordinate(lng, lat))
                    : null;
            Location location = Location.builder()
                    .activity(saved)
                    .geomPoint(point)
                    .addressText(addressText)
                    .build();
            locationRepository.save(location);
        }

        participantRepository.save(Participant.builder()
                .id(new ParticipantId(saved.getId(), creatorId))
                .activity(saved)
                .status(ParticipantStatus.JOINED)
                .build());

        for (UUID inviteeId : invitees) {
            if (inviteeId.equals(creatorId)) {
                continue;
            }
            participantRepository.save(Participant.builder()
                    .id(new ParticipantId(saved.getId(), inviteeId))
                    .activity(saved)
                    .status(ParticipantStatus.INVITED)
                    .build());
        }

        eventPublisher.publishEvent(new ActivityCreatedEvent(
                saved.getId(), creatorId, saved.getTitle(), saved.getStartTime(), invitees,
                audienceGroupId, Instant.now()));

        return saved;
    }

    /**
     * The group a GROUP-visibility plan is shared with, or null for every other
     * visibility.
     *
     * <p>Normalises rather than rejects when a group id arrives alongside PUBLIC, FRIENDS
     * or PRIVATE: the visibility is what the user chose, the group id is a leftover from a
     * form they changed their mind on, and the database would refuse the row anyway
     * ({@code chk_activities_group_visibility}) with a 409 that explains nothing.
     *
     * <p>Membership is required, not ownership - a group exists so its members can make
     * plans in it, not just whoever created it.
     */
    private UUID resolveGroupId(
            UUID creatorId, ge.kcamp.linkup.activity.enums.ActivityVisibility visibility, UUID groupId) {

        if (visibility != ge.kcamp.linkup.activity.enums.ActivityVisibility.GROUP) {
            return null;
        }
        if (groupId == null) {
            throw InvalidGroupException.missing();
        }
        if (!groupService.isMember(groupId, creatorId)) {
            throw InvalidGroupException.notAMember();
        }
        return groupId;
    }

    /**
     * Invitees must be accepted friends of the creator. Previously any UUID was written
     * straight into {@code participants}: an id belonging to nobody blew up as a foreign
     * key violation surfaced to the caller as a 500, and a valid id belonging to a
     * stranger was a working way to push an invitation at anyone.
     */
    private void requireFriends(UUID creatorId, List<UUID> inviteeIds) {
        if (inviteeIds.isEmpty()) {
            return;
        }
        Set<UUID> allowed = Set.copyOf(socialGraphService.getAcceptedFriendIds(creatorId));
        long rejected = inviteeIds.stream()
                .filter(id -> !id.equals(creatorId))
                .filter(id -> !allowed.contains(id))
                .distinct()
                .count();

        if (rejected > 0) {
            throw new InvalidInviteeException((int) rejected);
        }
    }
}
