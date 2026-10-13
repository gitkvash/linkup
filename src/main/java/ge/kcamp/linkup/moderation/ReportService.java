package ge.kcamp.linkup.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Files a report. Nothing here decides anything: the operator reads {@code reports} and acts.
 * That is why the log line carries the ids and the reason but never the reporter's free text.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportRepository reports;

    ReportService(ReportRepository reports) {
        this.reports = reports;
    }

    /**
     * @param details what the reporter wrote, or null; blank counts as none
     * @return true if this is a new report, false if the reporter had already reported the target
     */
    @Transactional
    public boolean report(UUID reporterId, ReportTargetType type, UUID targetId,
                          ReportReason reason, String details) {
        String cleaned = details == null || details.isBlank() ? null : details.strip();
        UUID id = UUID.randomUUID();
        boolean isNew = reports.file(id, reporterId, type.name(), targetId, reason.name(), cleaned) == 1;
        if (isNew) {
            log.warn("Report {} filed: {} {} for {}", id, type, targetId, reason);
        }
        return isNew;
    }
}
