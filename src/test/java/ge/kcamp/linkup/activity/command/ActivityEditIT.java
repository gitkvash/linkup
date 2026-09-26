package ge.kcamp.linkup.activity.command;

import ge.kcamp.linkup.AbstractIntegrationTest;
import ge.kcamp.linkup.activity.ActivityFeedItem;
import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.exception.ActivityNotVisibleException;
import ge.kcamp.linkup.activity.query.ActivityQueryRepository;
import ge.kcamp.linkup.activity.repository.ActivityRepository;
import ge.kcamp.linkup.activity.repository.LocationRepository;
import ge.kcamp.linkup.activity.repository.ParticipantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Editing and cancelling a plan.
 * <p>
 * Runs against a real database rather than mocks because the interesting parts are all
 * database-level: the {@code activity_update_policy}/{@code activity_delete_policy}
 * row-level policies have to permit these writes for the restricted app role (a policy
 * that matches nothing looks exactly like "no such plan"), and cancelling relies on the
 * {@code ON DELETE CASCADE} added in V14 to take participants and the location with it.
 */
@SpringBootTest
class ActivityEditIT extends AbstractIntegrationTest {

    @Autowired
    private ActivityCommandHandler commandHandler;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private ActivityQueryRepository queryRepository;

    /**
     * Each handler call commits on its own, as a request does (see
     * {@link AbstractIntegrationTest} for why this class has no test transaction), so the
     * JDBC read side sees it without a flush.
     */
    private Optional<ActivityFeedItem> readModel(UUID activityId, UUID viewerId) {
        return queryRepository.findById(activityId, viewerId);
    }

    @Test
    void updateReplacesTheEditableFieldsAndMovesTheLocation() {
        UUID creatorId = actAs(newUser());
        Activity created = createPlan(creatorId);

        commandHandler.handle(new UpdateActivityCommand(
                created.getId(), creatorId, "Basketball", ZonedDateTime.now().plusDays(2),
                null, true, 41.6938, 44.8015, "Vake Park", ActivityVisibility.PUBLIC, null, null,
                null, null, null));

        ActivityFeedItem updated = readModel(created.getId(), creatorId).orElseThrow();
        assertThat(updated.title()).isEqualTo("Basketball");
        assertThat(updated.visibility()).isEqualTo(ActivityVisibility.PUBLIC);
        assertThat(updated.addressText()).isEqualTo("Vake Park");
        assertThat(updated.lat()).isEqualTo(41.6938);
        assertThat(updated.lng()).isEqualTo(44.8015);
    }

    /** The creator joins their own plan at creation; an edit must not disturb that. */
    @Test
    void updateLeavesParticipantsAlone() {
        UUID creatorId = actAs(newUser());
        Activity created = createPlan(creatorId);

        commandHandler.handle(new UpdateActivityCommand(
                created.getId(), creatorId, "Renamed", ZonedDateTime.now().plusDays(1),
                null, true, 41.7151, 44.8271, "Rustaveli", ActivityVisibility.FRIENDS, null, null,
                null, null, null));

        assertThat(participantRepository.findByIdActivityIdOrderByStatusAsc(created.getId()))
                .hasSize(1);
        assertThat(readModel(created.getId(), creatorId).orElseThrow().viewerStatus())
                .isNotNull();
    }

    /**
     * A non-creator is refused as though the plan did not exist. 404 rather than 403 is
     * the point: a 403 confirms the id is real to anyone who guessed it.
     */
    @Test
    void onlyTheCreatorMayEditOrDelete() {
        UUID strangerId = newUser();
        UUID creatorId = actAs(newUser());
        Activity created = createPlan(creatorId);

        actAs(strangerId);
        assertThatThrownBy(() -> commandHandler.handle(new UpdateActivityCommand(
                created.getId(), strangerId, "Hijacked", ZonedDateTime.now().plusDays(1),
                null, true, 41.7, 44.8, "Somewhere", ActivityVisibility.PUBLIC, null, null,
                null, null, null)))
                .isInstanceOf(ActivityNotVisibleException.class);

        assertThatThrownBy(() ->
                commandHandler.handle(new DeleteActivityCommand(created.getId(), strangerId)))
                .isInstanceOf(ActivityNotVisibleException.class);

        actAs(creatorId);
        assertThat(activityRepository.findById(created.getId())).isPresent();
        assertThat(readModel(created.getId(), creatorId).orElseThrow().title())
                .isEqualTo("Football");
    }

    @Test
    void deleteTakesTheLocationAndParticipantsWithIt() {
        UUID creatorId = actAs(newUser());
        Activity created = createPlan(creatorId);
        UUID activityId = created.getId();

        assertThat(locationRepository.findByActivityId(activityId)).isPresent();

        commandHandler.handle(new DeleteActivityCommand(activityId, creatorId));

        assertThat(activityRepository.findById(activityId)).isEmpty();
        assertThat(locationRepository.findByActivityId(activityId)).isEmpty();
        assertThat(participantRepository.findByIdActivityIdOrderByStatusAsc(activityId)).isEmpty();
        assertThat(readModel(activityId, creatorId)).isEmpty();
    }

    /** An edit that clears both the coordinates and the address drops the row. */
    @Test
    void updateWithNoPlaceRemovesTheLocation() {
        UUID creatorId = actAs(newUser());
        Activity created = createPlan(creatorId);

        commandHandler.handle(new UpdateActivityCommand(
                created.getId(), creatorId, "Somewhere, later", ZonedDateTime.now().plusDays(3),
                null, false, null, null, null, ActivityVisibility.FRIENDS, null, null,
                null, null, null));

        assertThat(locationRepository.findByActivityId(created.getId())).isEmpty();
        ActivityFeedItem updated = readModel(created.getId(), creatorId).orElseThrow();
        assertThat(updated.lat()).isNull();
        assertThat(updated.addressText()).isNull();
        assertThat(updated.hasTime()).isFalse();
    }

    private Activity createPlan(UUID creatorId) {
        return commandHandler.handle(new CreateStructuredActivityCommand(
                creatorId, "Football", ZonedDateTime.now().plusDays(1), null, true,
                41.7151, 44.8271, "Rustaveli", ActivityVisibility.FRIENDS, null, List.of(), null,
                null, null, null));
    }
}
