package ge.kcamp.linkup.activity.command;

import java.util.UUID;

/**
 * @param actorId who is asking. Only the creator may cancel a plan; anyone else gets the
 *                same 404 a stranger gets for an id that doesn't exist.
 */
public record DeleteActivityCommand(UUID activityId, UUID actorId) {
}
