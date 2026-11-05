package ru.tsu.accounts.web;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.client.ClientService;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@Controller
@PreAuthorize("hasRole('OPERATOR')")
public class AccountOpeningController {
    private static final Logger log = LoggerFactory.getLogger(AccountOpeningController.class);
    private final AccountService accounts;
    private final ClientService clients;
    private final AuditAttemptService attempts;
    public AccountOpeningController(AccountService accounts, ClientService clients, AuditAttemptService attempts) {
        this.accounts = accounts; this.clients = clients; this.attempts = attempts;
    }
    @GetMapping("/clients/{id}/accounts/new")
    public String newPage(@PathVariable UUID id, Model model) {
        model.addAttribute("client", clients.get(id));
        model.addAttribute("openingId", UUID.randomUUID());
        model.addAttribute("openedOn", LocalDate.now(ZoneId.of("Europe/Moscow")));
        return "accounts/new";
    }
    @PostMapping("/clients/{id}/accounts/new")
    public String open(@PathVariable UUID id, @RequestParam UUID openingId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate openedOn,
                       @AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request,
                       HttpServletResponse response, Model model) {
        try {
            var account = accounts.open(id, openingId, openedOn,
                actor.id(), actor.username(), RequestIdFilter.id(request));
            return "redirect:/accounts/" + account.id() + "?created";
        } catch (UserCommandException e) {
            response.setStatus(e.status().value());
            model.addAttribute("client", clients.get(id));
            model.addAttribute("openingId", openingId); model.addAttribute("openedOn", openedOn);
            model.addAttribute("formError", e.getMessage());
            try {
                attempts.record(AuditEntry.denied(actor.id(), actor.username(), "BUSINESS", "ACCOUNT_OPEN_DENIED",
                    "CLIENT", id.toString(), e.reasonCode(), RequestIdFilter.id(request)));
            } catch (Exception failure) {
                log.error("Account opening denial audit unavailable; requestId={}", RequestIdFilter.id(request), failure);
            }
            return "accounts/new";
        }
    }
}
