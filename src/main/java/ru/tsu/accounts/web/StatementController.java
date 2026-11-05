package ru.tsu.accounts.web;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.statement.DocumentAuditService;
import ru.tsu.accounts.statement.DocumentExportService;
import ru.tsu.accounts.statement.StatementService;

@Controller
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
public class StatementController {
    private final StatementService statements;
    private final DocumentExportService export;
    private final DocumentAuditService audit;

    public StatementController(StatementService statements, DocumentExportService export,
                               DocumentAuditService audit) {
        this.statements = statements; this.export = export; this.audit = audit;
    }

    @GetMapping("/accounts/{id}/statement")
    public String parameters(@PathVariable UUID id, @RequestParam(defaultValue="") String from,
        @RequestParam(defaultValue="") String to, Model model, HttpServletResponse response) {
        var account = statements.account(id);
        var today = LocalDate.now(ZoneId.of("Europe/Moscow"));
        var suggestedFrom = today.minusDays(30).isBefore(account.historyStartOn())
            ? account.historyStartOn() : today.minusDays(30);
        model.addAttribute("account", account);
        model.addAttribute("from", from.isBlank() ? suggestedFrom.toString() : from);
        model.addAttribute("to", to.isBlank() ? today.toString() : to);
        noStore(response);
        return "statements/parameters";
    }

    @GetMapping({"/accounts/{id}/statement/result", "/accounts/{id}/statement/print"})
    public String result(@PathVariable UUID id, @RequestParam(defaultValue="") String from,
        @RequestParam(defaultValue="") String to, HttpServletRequest request,
        HttpServletResponse response, Model model) {
        ru.tsu.accounts.statement.Statement statement;
        try {
            statement = statements.generate(id, from, to);
        } catch (UserCommandException ex) {
            if (ex.status() != HttpStatus.BAD_REQUEST || !"STATEMENT_VALIDATION".equals(ex.reasonCode()))
                throw ex;
            String view = parameters(id, from, to, model, response);
            // Keep the user's exact input, including an intentionally empty date.
            model.addAttribute("from", from);
            model.addAttribute("to", to);
            model.addAttribute("formError", ex.getMessage());
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return view;
        }
        boolean print = request.getRequestURI().endsWith("/print");
        audit.record(statement, print ? "PRINT" : "HTML", RequestIdFilter.id(request));
        model.addAttribute("statement", statement);
        model.addAttribute("printView", print);
        noStore(response);
        return "statements/result";
    }

    @GetMapping("/accounts/{id}/statement.csv")
    public void csv(@PathVariable UUID id, @RequestParam(defaultValue="") String from,
        @RequestParam(defaultValue="") String to, HttpServletRequest request,
        HttpServletResponse response) throws IOException {
        // No connection remains open while a slow or disconnected client receives the file.
        try (var document = export.createCsv(id, from, to)) {
            audit.record(document.metadata(), "CSV", RequestIdFilter.id(request));
            noStore(response);
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                .filename(document.filename()).build().toString());
            response.setContentLengthLong(document.contentLength());
            Files.copy(document.path(), response.getOutputStream());
        }
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
