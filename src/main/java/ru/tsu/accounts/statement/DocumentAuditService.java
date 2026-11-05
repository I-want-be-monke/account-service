package ru.tsu.accounts.statement;

import java.util.Map;
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

/** Audits an authorized document after its read transaction and before any response bytes. */
@Service
public class DocumentAuditService {
    private static final Logger log = LoggerFactory.getLogger(DocumentAuditService.class);
    private final AuditAttemptService audit;

    public DocumentAuditService(AuditAttemptService audit) { this.audit = audit; }

    public void record(Statement statement, String format, UUID requestId) {
        var actor = (AccountPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        try {
            audit.record(new AuditEntry(actor.id(), actor.username(), "USER", "BUSINESS",
                "STATEMENT_GENERATED", "ACCOUNT", statement.accountId().toString(), "SUCCESS", null,
                requestId, null, null, Map.of("format", format, "from", statement.from().toString(),
                    "to", statement.to().toString(), "formatVersion", statement.formatVersion())));
        } catch (RuntimeException ex) {
            log.error("Document audit unavailable; requestId={}", requestId, ex);
            throw new UserCommandException(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_AUDIT_UNAVAILABLE",
                "Выписка не выдана: журналирование временно недоступно. Повторите позже.");
        }
    }
}
