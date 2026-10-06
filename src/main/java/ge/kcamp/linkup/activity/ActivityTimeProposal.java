package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.TimeProposalStatus;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A "suggest another time" request as the detail screen shows it. {@code username} may be
 * null if the account has since been removed.
 */
public record ActivityTimeProposal(
        UUID id,
        UUID activityId,
        UUID proposerId,
        String username,
        ZonedDateTime startTime,
        ZonedDateTime endTime,
        String message,
        TimeProposalStatus status,
        ZonedDateTime createdAt
) {
}
