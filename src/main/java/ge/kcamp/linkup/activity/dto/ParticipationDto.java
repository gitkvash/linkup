package ge.kcamp.linkup.activity.dto;

import ge.kcamp.linkup.activity.enums.ParticipantStatus;

/**
 * The caller's participation after a join/leave/respond, so the client can update
 * its button without re-fetching the activity.
 */
public record ParticipationDto(ParticipantStatus viewerStatus) {
}
