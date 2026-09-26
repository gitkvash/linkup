package ge.kcamp.linkup.activity;

import ge.kcamp.linkup.activity.entity.Activity;
import ge.kcamp.linkup.activity.enums.ActivityCategory;
import ge.kcamp.linkup.activity.enums.ActivityType;
import ge.kcamp.linkup.activity.enums.ActivityVisibility;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ActivityFactory {

    /**
     * @param groupId  the audience for {@code GROUP} visibility, null for every other
     *                 value. Membership is the caller's business to check - see
     *                 {@code ActivityCommandHandler.resolveGroupId}.
     * @param category null defaults to {@link ActivityCategory#GENERAL}.
     */
    public Activity createFrom(
            ActivitySpec spec, UUID creatorId, ActivityVisibility visibility, UUID groupId,
            ActivityCategory category) {
        ActivityCategory resolvedCategory = category != null ? category : ActivityCategory.GENERAL;
        return switch (spec) {
            case CasualPlanSpec casual -> Activity.builder()
                    .creatorId(creatorId)
                    .activityType(ActivityType.CASUAL_PLAN)
                    .title(casual.title())
                    .visibility(visibility)
                    .groupId(groupId)
                    .startTime(casual.approximateStartTime())
                    .hasTime(casual.hasTime())
                    .category(resolvedCategory)
                    .build();
            case StructuredEventSpec structured -> Activity.builder()
                    .creatorId(creatorId)
                    .activityType(ActivityType.SPECIFIC_EVENT)
                    .title(structured.title())
                    .visibility(visibility)
                    .groupId(groupId)
                    .startTime(structured.startTime())
                    .endTime(structured.endTime())
                    .hasTime(structured.hasTime())
                    .category(resolvedCategory)
                    .repeatFrequency(structured.repeatFrequency())
                    // The interval is only ever written alongside a frequency, and the
                    // database refuses the row otherwise (chk_activities_repeat).
                    .repeatInterval(structured.repeatFrequency() == null
                            ? null
                            : structured.repeatInterval())
                    .repeatUntil(structured.repeatFrequency() == null
                            ? null
                            : structured.repeatUntil())
                    .build();
        };
    }
}
