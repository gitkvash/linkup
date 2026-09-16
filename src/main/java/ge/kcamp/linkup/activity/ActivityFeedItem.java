package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityStatus;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.ParticipantStatus;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Read-model shape for the query side of activity CQRS - a denormalized projection over
 * activities+locations+participants, part of the {@code activity} module's public API.
 *
 * @param participantCount people who have actually JOINED. It previously counted every
 *                         participant row, so an activity nobody accepted still reported
 *                         a headcount.
 * @param viewerStatus     the requesting user's own participation, or null if they are
 *                         not a participant. Lets the client show the right action
 *                         (join / accept / already in) without a second request.
 * @param groupId          the audience for {@code GROUP} visibility, null otherwise.
 * @param groupName        that group's name, so the client can say "Group · Riders"
 *                         instead of just "Group" and leaving the user to guess which.
 * @param repeatFrequency  null for a plan that happens once. When set, the plan repeats
 *                         every {@code repeatInterval} of that unit until
 *                         {@code repeatUntil} (null = no end date). Nothing is
 *                         materialised per occurrence, so the client renders the rule -
 *                         see {@code V24__activity_recurrence.sql}.
 * @param status           where the plan is in its own life - upcoming, live or over.
 *                         Derived on every read rather than stored; see
 *                         {@link ActivityStatusResolver}.
 * @param creatorDisplayName what the creator calls themselves, when they have set
 *                         anything. Null falls back to {@code creatorUsername} - the
 *                         client renders one line, and which name fills it is a
 *                         question with one answer, not one per screen.
 * @param creatorUsername  who made the plan. Carried for the same reason
 *                         {@code FeedItemDto} carries it: the client had only
 *                         {@code creatorId}, so the detail screen's "Organiser" row
 *                         showed every plan - including your own - as "User aa0d…2ef".
 *                         Null if the account has since been removed.
 */
public record ActivityFeedItem(
        UUID activityId,
        UUID creatorId,
        String creatorUsername,
        String creatorDisplayName,
        String title,
        ActivityType activityType,
        ActivityVisibility visibility,
        ZonedDateTime startTime,
        ZonedDateTime endTime,
        boolean hasTime,
        String addressText,
        Double lat,
        Double lng,
        long participantCount,
        ParticipantStatus viewerStatus,
        UUID groupId,
        String groupName,
        ActivityCategory category,
        RepeatFrequency repeatFrequency,
        Integer repeatInterval,
        ZonedDateTime repeatUntil,
        ActivityStatus status
) {
}
