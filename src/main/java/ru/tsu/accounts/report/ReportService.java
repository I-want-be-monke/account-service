package ru.tsu.accounts.report;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    private static final String ROLES = "hasAnyRole('ADMIN','ACCOUNTANT','MANAGER')";
    private final ReportReadRepository repository;

    ReportService(ReportReadRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
        propagation = Propagation.REQUIRES_NEW)
    @PreAuthorize(ROLES)
    public Report generate(ReportQuery query) {
        var projection = repository.projection(query);
        var summary = repository.summarize(projection);
        Instant generatedAt = Instant.now();
        long offset = (long) query.page() * query.size();
        List<ReportRow> rows = offset >= summary.totalRows() ? List.of()
            : repository.page(projection, query.size(), offset);
        return new Report(query, generatedAt, summary.totalRows(), summary.totals(), rows);
    }

    /** The sink prepares a local export before the report leaves its database snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
        propagation = Propagation.REQUIRES_NEW)
    @PreAuthorize(ROLES)
    public Report scan(ReportQuery query, ReportSink sink) {
        Objects.requireNonNull(sink);
        var projection = repository.projection(query);
        var summary = repository.summarize(projection);
        var metadata = new Report(query, Instant.now(), summary.totalRows(), summary.totals(), List.of());
        sink.start(metadata);
        if (summary.totalRows() != 0) repository.visit(projection, sink::row);
        sink.finish(metadata);
        return metadata;
    }

    public interface ReportSink {
        default void start(Report metadata) {}
        default void row(ReportRow row) {}
        default void finish(Report metadata) {}
    }
}
