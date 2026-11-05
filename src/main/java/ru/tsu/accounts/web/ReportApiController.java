package ru.tsu.accounts.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.report.ReportAuditService;
import ru.tsu.accounts.report.ReportQuery;
import ru.tsu.accounts.report.ReportService;
import ru.tsu.accounts.report.ReportType;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','MANAGER')")
public class ReportApiController {
    private final ReportService reports;
    private final ReportAuditService audit;

    public ReportApiController(ReportService reports, ReportAuditService audit) {
        this.reports = reports;
        this.audit = audit;
    }

    @GetMapping("/api/v1/reports/{type}")
    public ResponseEntity<ReportView> report(@PathVariable String type,
        @RequestParam MultiValueMap<String, String> parameters, HttpServletRequest request) {
        var query = ReportQuery.parse(type, ReportWebParameters.singleValues(parameters));
        var report = reports.generate(query);
        audit.record(report, "JSON", RequestIdFilter.id(request));
        var rows = report.rows().stream().map(row -> new RowView(row.accountId(), row.accountNumber(),
            row.clientId(), row.clientName(), row.status(), row.amounts(),
            request.getContextPath() + "/accounts/" + row.accountId())).toList();
        var view = new ReportView(query.type().name(), ReportWebParameters.filters(query), report.generatedAt(),
            "RUB", report.totalRows(), ReportWebParameters.totalPages(report.totalRows(), query.size()),
            query.page(), query.size(), query.type().getColumns(), report.totals(), rows);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    public record ReportView(String type, Map<String, String> parameters, Instant generatedAt, String currency,
        long totalRows, long totalPages, int page, int size, List<ReportType.Column> columns,
        Map<String, String> totals, List<RowView> rows) {}

    public record RowView(UUID accountId, String accountNumber, UUID clientId, String clientName,
        String status, Map<String, String> amounts, String accountUrl) {}
}
