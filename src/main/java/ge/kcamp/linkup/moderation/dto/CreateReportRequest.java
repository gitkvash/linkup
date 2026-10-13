package ge.kcamp.linkup.moderation.dto;

import ge.kcamp.linkup.moderation.ReportReason;
import ge.kcamp.linkup.moderation.ReportTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateReportRequest(
        @NotNull ReportTargetType targetType,
        @NotNull UUID targetId,
        @NotNull ReportReason reason,
        @Size(max = 500) String details
) {
}
