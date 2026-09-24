package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.activity.ActivityCancelledEvent;
import ge.kcamp.linkup.activity.ActivityDeletedEvent;
import ge.kcamp.linkup.activity.ActivityUpdatedEvent;
import ge.kcamp.linkup.activity.CasualPlanSpec;
import ge.kcamp.linkup.activity.StructuredEventSpec;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Location;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.LocationRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import ge.kcamp.linkup.nlp.NlpParserService;
import ge.kcamp.linkup.nlp.ParsedActivityText;
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
    private final NlpParserService nlpParserService;
    private final ActivityPersistenceService activityPersistenceService;
    private final ParticipantRepository participantRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), WGS84_SRID);

    public ActivityCommandHandler(
            ActivityRepository activityRepository,
            LocationRepository locationRepository,
            NlpParserService nlpParserService,
            ActivityPersistenceService activityPersistenceService,
            ParticipantRepository participantRepository,
            ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.locationRepository = locationRepository;
        this.nlpParserService = nlpParserService;
        this.activityPersistenceService = activityPersistenceService;
        this.participantRepository = participantRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Deliberately not {@code @Transactional}: {@link ActivityPersistenceService}'s own
     * annotation opens the transaction, and only after the CoreNLP parse below has run.
     * Wrapping this method would open it first and hold a pooled JDBC connection idle for
     * the multi-second parse - see the comment on {@code hibernate.open-in-view} in
     * application.yaml, and {@link ActivityPersistenceService}'s class Javadoc for why the
     * split had to be a separate bean rather than a private method here.
     */
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
        // A date-only plan is stored at the user's local midnight, as the form and edit
        // screens send it, not at the parser's guessed hour: the zone isn't stored, so
        // "start plus a day" is the only way ActivityStatusResolver can find its day's end.
        if (!hasTime) {
            startTime = startTime.withZoneSameInstant(zone).toLocalDate().atStartOfDay(zone);
        }

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

        return activityPersistenceService.createAndPersist(spec, command.creatorId(),
                command.visibility(), command.groupId(), null, null, locationText,
                command.inviteeUserIds(), null);
    }

    public Activity handle(CreateStructuredActivityCommand command) {
        StructuredEventSpec spec = new StructuredEventSpec(
                command.title(), command.startTime(), command.endTime(), command.hasTime(),
                command.addressText(), command.lat(), command.lng(),
                command.repeatFrequency(), command.repeatInterval(), command.repeatUntil());

        return activityPersistenceService.createAndPersist(spec, command.creatorId(),
                command.visibility(), command.groupId(), command.lat(), command.lng(),
                command.addressText(), command.inviteeUserIds(), command.category());
    }

    /**
     * Applies an edit to an existing plan.
     * <p>
     * The whole editable surface is replaced, matching what the client's edit form
     * sends. Participants are untouched: who is coming is changed through join/respond,
     * and re-deriving it here would resurrect invitations people had already declined.
     * <p>
     * Publishes {@link ActivityUpdatedEvent}, not {@code ActivityCreatedEvent}: that one
     * means "a new plan exists", and the ACTIVITY_INVITE notification and first fan-out
     * that follow from it are not true of an edit. With no event at all, a timeline
     * entry kept the Redis score it was written with, so a moved start time left the
     * stored score and the one the feed's cursor is computed from disagreeing - the plan
     * was skipped or repeated at a page boundary. Published inside this transaction, the
     * way {@code ActivityCreatedEvent} is, so it is only registered if the edit commits.
     */
    @Transactional
    public Activity handle(UpdateActivityCommand command) {
        Activity activity = requireOwned(command.activityId(), command.actorId());

        UUID audienceGroupId = activityPersistenceService.resolveGroupId(
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

        eventPublisher.publishEvent(new ActivityUpdatedEvent(
                saved.getId(), saved.getCreatorId(), saved.getStartTime(), audienceGroupId, Instant.now()));
        return saved;
    }

    /**
     * Cancels a plan. {@code participants} and {@code locations} go with it through
     * {@code ON DELETE CASCADE} (V14). Publishes {@link ActivityDeletedEvent}, in this
     * transaction, so the feed can drop the id from the timelines it was fanned out to
     * rather than keeping it until it ages out. And {@link ActivityCancelledEvent}, for
     * the people who were in it - whose ids have to be read here, before the cascade
     * takes their rows.
     */
    @Transactional
    public void handle(DeleteActivityCommand command) {
        Activity activity = requireOwned(command.activityId(), command.actorId());
        List<UUID> participants = participantRepository
                .findUserIdsByActivityAndStatusIn(activity.getId(), ParticipantRepository.IN_THE_PLAN)
                .stream()
                .filter(userId -> !userId.equals(activity.getCreatorId()))
                .toList();
        activityRepository.delete(activity);
        Instant now = Instant.now();
        eventPublisher.publishEvent(new ActivityDeletedEvent(
                activity.getId(), activity.getCreatorId(), now));
        if (!participants.isEmpty()) {
            eventPublisher.publishEvent(new ActivityCancelledEvent(
                    activity.getId(), activity.getCreatorId(), activity.getTitle(),
                    activity.getStartTime(), activity.isHasTime(), participants, now));
        }
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
}
