package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.ParticipantStatus;

import java.util.UUID;

/**
 * One person's involvement in an activity. {@code username} may be null if the
 * account has since been removed.
 */
public record ActivityParticipant(UUID userId, String username, ParticipantStatus status) {
}
