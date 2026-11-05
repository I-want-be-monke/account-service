package ru.tsu.accounts.web;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.statement.DocumentAuditService;
import ru.tsu.accounts.statement.Statement;
import ru.tsu.accounts.statement.StatementService;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
public class StatementApiController {
    private final StatementService statements;
    private final DocumentAuditService audit;

    public StatementApiController(StatementService statements, DocumentAuditService audit) {
        this.statements = statements; this.audit = audit;
    }

    @GetMapping("/api/v1/accounts/{id}/statement")
    public ResponseEntity<Statement> statement(@PathVariable UUID id,
        @RequestParam(defaultValue="") String from, @RequestParam(defaultValue="") String to,
        HttpServletRequest request) {
        var statement = statements.generate(id, from, to);
        audit.record(statement, "JSON", RequestIdFilter.id(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(statement);
    }
}
