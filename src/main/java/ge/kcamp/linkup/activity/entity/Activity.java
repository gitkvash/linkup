package ge.kcamp.linkup.activity.entity;

import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import ge.kcamp.linkup.activity.enums.RepeatFrequency;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.ZonedDateTime;
import java.util.UUID;

@Setter
@Getter
@Entity
@Table(name = "activities")
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "activity_id")
    private UUID id;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 30)
    private ActivityType activityType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private ActivityVisibility visibility;

    /**
     * Set if and only if {@link #visibility} is {@code GROUP}. The database enforces both
     * directions ({@code chk_activities_group_visibility}): a GROUP plan with no group is
     * invisible to the people it was made for, and a group id on a PUBLIC plan is a claim
     * the visibility rules do not honour.
     */
    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "start_time", nullable = false)
    private ZonedDateTime startTime;

    @Column(name = "end_time")
    private ZonedDateTime endTime;

    /**
     * False when only a date was given, no clock time - {@link #startTime} is still a
     * real timestamp (midnight local) so every query keeps working, but the client
     * should show "tomorrow" or "Jul 3", not a time of day that was never chosen.
     */
    @Column(name = "has_time", nullable = false)
    private boolean hasTime = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private ActivityCategory category = ActivityCategory.GENERAL;

    /**
     * Null for a one-off plan, which is every plan created before this column existed.
     * When set, the plan repeats every {@link #repeatInterval} of this unit from
     * {@link #startTime}; the repetitions are not materialised as rows (see {@code V24}),
     * so this is a rule the client renders, not a series it can join one occurrence of.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "repeat_freq", length = 10)
    private RepeatFrequency repeatFrequency;

    /** Set if and only if {@link #repeatFrequency} is - enforced by {@code chk_activities_repeat}. */
    @Column(name = "repeat_interval")
    private Integer repeatInterval;

    /** When the repetition stops. Null alongside a frequency means "no end date". */
    @Column(name = "repeat_until")
    private ZonedDateTime repeatUntil;

    public Activity() {}

    public Activity(UUID id, UUID creatorId, ActivityType activityType, String title, ActivityVisibility visibility, UUID groupId, ZonedDateTime startTime, ZonedDateTime endTime, boolean hasTime, ActivityCategory category, RepeatFrequency repeatFrequency, Integer repeatInterval, ZonedDateTime repeatUntil) {
        this.id = id;
        this.creatorId = creatorId;
        this.activityType = activityType;
        this.title = title;
        this.visibility = visibility;
        this.groupId = groupId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.hasTime = hasTime;
        this.category = category;
        this.repeatFrequency = repeatFrequency;
        this.repeatInterval = repeatInterval;
        this.repeatUntil = repeatUntil;
    }

    public static ActivityBuilder builder() {
        return new ActivityBuilder();
    }

    public static class ActivityBuilder {
        private UUID id;
        private UUID creatorId;
        private ActivityType activityType;
        private String title;
        private ActivityVisibility visibility;
        private UUID groupId;
        private ZonedDateTime startTime;
        private ZonedDateTime endTime;
        private boolean hasTime = true;
        private ActivityCategory category = ActivityCategory.GENERAL;
        private RepeatFrequency repeatFrequency;
        private Integer repeatInterval;
        private ZonedDateTime repeatUntil;

        public ActivityBuilder id(UUID id) { this.id = id; return this; }
        public ActivityBuilder creatorId(UUID creatorId) { this.creatorId = creatorId; return this; }
        public ActivityBuilder activityType(ActivityType activityType) { this.activityType = activityType; return this; }
        public ActivityBuilder title(String title) { this.title = title; return this; }
        public ActivityBuilder visibility(ActivityVisibility visibility) { this.visibility = visibility; return this; }
        public ActivityBuilder groupId(UUID groupId) { this.groupId = groupId; return this; }
        public ActivityBuilder startTime(ZonedDateTime startTime) { this.startTime = startTime; return this; }
        public ActivityBuilder endTime(ZonedDateTime endTime) { this.endTime = endTime; return this; }
        public ActivityBuilder hasTime(boolean hasTime) { this.hasTime = hasTime; return this; }
        public ActivityBuilder category(ActivityCategory category) { this.category = category; return this; }
        public ActivityBuilder repeatFrequency(RepeatFrequency repeatFrequency) { this.repeatFrequency = repeatFrequency; return this; }
        public ActivityBuilder repeatInterval(Integer repeatInterval) { this.repeatInterval = repeatInterval; return this; }
        public ActivityBuilder repeatUntil(ZonedDateTime repeatUntil) { this.repeatUntil = repeatUntil; return this; }

        public Activity build() {
            return new Activity(id, creatorId, activityType, title, visibility, groupId, startTime, endTime, hasTime, category, repeatFrequency, repeatInterval, repeatUntil);
        }
    }
}
