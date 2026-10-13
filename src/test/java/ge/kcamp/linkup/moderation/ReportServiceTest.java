package ge.kcamp.linkup.moderation;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private final ReportRepository repository = mock(ReportRepository.class);
    private final ReportService service = new ReportService(repository);
    private final UUID reporter = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void aNewReportIsFiledAsTheReporterWithTheNamesTheTableChecks() {
        when(repository.file(any(), any(), anyString(), any(), anyString(), any())).thenReturn(1);

        boolean isNew = service.report(reporter, ReportTargetType.USER, target, ReportReason.HARASSMENT, "rude");

        assertThat(isNew).isTrue();
        verify(repository).file(any(), eq(reporter), eq("USER"), eq(target), eq("HARASSMENT"), eq("rude"));
    }

    @Test
    void reportingTheSameTargetAgainIsNotASecondReport() {
        when(repository.file(any(), any(), anyString(), any(), anyString(), any())).thenReturn(0);

        assertThat(service.report(reporter, ReportTargetType.ACTIVITY, target, ReportReason.SPAM, null)).isFalse();
    }

    @Test
    void blankDetailsAreStoredAsNone() {
        when(repository.file(any(), any(), anyString(), any(), anyString(), any())).thenReturn(1);

        service.report(reporter, ReportTargetType.USER, target, ReportReason.OTHER, "   ");

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(repository).file(any(), any(), anyString(), any(), anyString(), details.capture());
        assertThat(details.getValue()).isNull();
    }

    /** V44's check constraints list these by name; a new constant must be added there too. */
    @Test
    void everyReasonAndTargetFitsTheirCheckConstraint() {
        assertThat(ReportReason.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "HARASSMENT", "SPAM", "UNSAFE", "ILLEGAL", "INAPPROPRIATE", "IMPERSONATION", "OTHER");
        assertThat(ReportTargetType.values()).extracting(Enum::name).containsExactlyInAnyOrder("USER", "ACTIVITY");
    }
}
