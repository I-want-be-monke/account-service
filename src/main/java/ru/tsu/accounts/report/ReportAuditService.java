package ru.tsu.accounts.report;

import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@Service
public class ReportAuditService {
    private static final Logger log = LoggerFactory.getLogger(ReportAuditService.class);
    private final AuditAttemptService audit;

    public ReportAuditService(AuditAttemptService audit) {
        this.audit = audit;
    }

    public void record(Report report, String format, UUID requestId) {
        var actor = (AccountPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var query = report.query();
        var details = new LinkedHashMap<String, Object>();
        details.put("format", format);
        details.put("from", query.from().toString());
        details.put("to", query.to().toString());
        details.put("accountCount", report.totalRows());
        if (query.clientId() != null) details.put("clientId", query.clientId().toString());
        if (query.status() != null) details.put("status", query.status());
        try {
            audit.record(new AuditEntry(actor.id(), actor.username(), "USER", "BUSINESS",
                "REPORT_GENERATED", "REPORT", query.type().name(), "SUCCESS", null,
                requestId, null, null, details));
        } catch (RuntimeException ex) {
            log.error("Report audit unavailable [requestId={}]", requestId, ex);
            throw new UserCommandException(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_AUDIT_UNAVAILABLE",
                "Отчёт будет доступен после восстановления журнала. Повторите позже.");
        }
    }
}
