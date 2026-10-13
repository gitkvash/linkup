package ge.kcamp.linkup.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Exists only so {@link ReportRepository} has a type to extend. Nothing loads or saves one:
 * Hibernate validates the mapping against V44, and every write goes through {@code file}.
 */
@Entity
@Table(name = "reports")
class ReportRow {

    @Id
    @Column(name = "report_id")
    private UUID id;

    protected ReportRow() {
    }
}
