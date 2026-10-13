package ge.kcamp.linkup.moderation.controller;

import ge.kcamp.linkup.UserContext;
import ge.kcamp.linkup.moderation.ReportService;
import ge.kcamp.linkup.moderation.dto.CreateReportRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Report a person or a plan. Always 204, including for a repeat of an earlier report and for
     * a target that no longer exists: the reporter learns nothing about either, and a retry after
     * a lost response is harmless.
     */
    @PostMapping
    public ResponseEntity<Void> report(@Valid @RequestBody CreateReportRequest request) {
        reportService.report(UserContext.getUserId(), request.targetType(), request.targetId(),
                request.reason(), request.details());
        return ResponseEntity.noContent().build();
    }
}
