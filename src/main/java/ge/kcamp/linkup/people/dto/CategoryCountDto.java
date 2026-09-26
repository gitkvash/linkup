package ge.kcamp.linkup.people.dto;

import ge.kcamp.linkup.activity.enums.ActivityCategory;

public record CategoryCountDto(ActivityCategory category, long count) {
}
