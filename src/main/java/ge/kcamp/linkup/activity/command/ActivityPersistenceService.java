package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.activity.ActivityCreatedEvent;
import ge.kcamp.linkup.activity.ActivityFactory;
import ge.kcamp.linkup.activity.ActivitySpec;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.entity.Location;
import ge.kcamp.linkup.activity.entity.Participant;
import ge.kcamp.linkup.activity.entity.ParticipantId;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.exception.InvalidGroupException;
import ge.kcamp.linkup.activity.exception.InvalidInviteeException;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.LocationRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The transactional write path for a plan, shared by both {@link ActivityCommandHandler}
 * entry points. Split out from the handler so the DB transaction only ever spans this - in
 * particular, never the multi-second CoreNLP parse that {@code CreateActivityFromTextCommand}
 * runs before reaching here (see the handler's Javadoc). A Spring proxy only applies
 * {@code @Transactional} across a bean boundary, which is the other reason this had to be a
 * separate bean rather than a private method the handler calls into.
 */
@Service
public class ActivityPersistenceService {

    private static final int WGS84_SRID = 4326;

    private final ActivityRepository activityRepository;
    private final LocationRepository locationRepository;
    private final ParticipantRepository participantRepository;
    private final ActivityFactory activityFactory;
    private final SocialGraphService socialGraphService;
    private final GroupService groupService;
    private final ApplicationEventPublisher eventPublisher;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), WGS84_SRID);

    public ActivityPersistenceService(
            ActivityRepository activityRepository,
            LocationRepository locationRepository,
            ParticipantRepository participantRepository,
            ActivityFactory activityFactory,
            SocialGraphService socialGraphService,
            GroupService groupService,
            ApplicationEventPublisher eventPublisher) {
        this.activityRepository = activityRepository;
        this.locationRepository = locationRepository;
        this.participantRepository = participantRepository;
        this.activityFactory = activityFactory;
        this.socialGraphService = socialGraphService;
        this.groupService = groupService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Activity createAndPersist(
            ActivitySpec spec,
            UUID creatorId,
            ActivityVisibility visibility,
            UUID groupId,
            Double lat,
            Double lng,
            String addressText,
            List<UUID> inviteeUserIds,
            ActivityCategory category) {

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
    public UUID resolveGroupId(UUID creatorId, ActivityVisibility visibility, UUID groupId) {
        if (visibility != ActivityVisibility.GROUP) {
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
     * <p>
     * Public so inviting people to an existing plan goes through the same rule.
     */
    public void requireFriends(UUID creatorId, List<UUID> inviteeIds) {
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
