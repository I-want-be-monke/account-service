package ru.tsu.accounts.web;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.account.AccountStatusConfirmation;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@Controller
@RequestMapping("/accounts")
public class AccountController {
    private static final Logger log = LoggerFactory.getLogger(AccountController.class);
    private final AccountService accounts;
    private final AccountStatusConfirmation confirmations;
    private final AuditAttemptService attempts;
    public AccountController(AccountService accounts, AccountStatusConfirmation confirmations,
                             AuditAttemptService attempts) {
        this.accounts = accounts; this.confirmations = confirmations; this.attempts = attempts;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER')")
    public String list(@RequestParam(defaultValue = "") String number,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(required = false) UUID clientId,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("accounts", accounts.search(number, status, clientId, page));
        model.addAttribute("number", number); model.addAttribute("status", status);
        model.addAttribute("clientId", clientId);
        return "accounts/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                         @AuthenticationPrincipal AccountPrincipal actor, Model model) {
        model.addAttribute("account", accounts.get(id));
        model.addAttribute("history", accounts.history(id, page));
        model.addAttribute("isOperator", actor.role().equals("OPERATOR"));
        model.addAttribute("isClient", actor.role().equals("CLIENT"));
        return "accounts/detail";
    }

    @GetMapping("/{id}/status")
    @PreAuthorize("hasRole('OPERATOR')")
    public String statusPage(@PathVariable UUID id, Model model) {
        model.addAttribute("account", accounts.get(id));
        return "accounts/status";
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('OPERATOR')")
    public String prepare(@PathVariable UUID id, @RequestParam long version,
                          @RequestParam String status, @RequestParam String reason,
                          @AuthenticationPrincipal AccountPrincipal actor,
                          HttpServletRequest request, HttpServletResponse response, Model model) {
        try {
            accounts.validateStatus(id, version, status, reason);
            confirmations.prepare(request.getSession(), actor.id(), id, version, status, reason);
            return "redirect:/accounts/" + id + "/confirm";
        } catch (UserCommandException e) {
            denied(actor, request, "ACCOUNT_STATUS_DENIED", id, e);
            response.setStatus(e.status().value());
            model.addAttribute("formError", e.getMessage());
            model.addAttribute("account", accounts.get(id));
            return "accounts/status";
        }
    }

    @GetMapping("/{id}/confirm")
    @PreAuthorize("hasRole('OPERATOR')")
    public String confirmPage(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal actor,
                              HttpSession session, Model model) {
        model.addAttribute("account", accounts.get(id));
        model.addAttribute("pending", confirmations.view(session, actor.id(), id));
        return "accounts/confirm";
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('OPERATOR')")
    public String confirm(@PathVariable UUID id, @RequestParam String token,
                          @AuthenticationPrincipal AccountPrincipal actor, HttpSession session,
                          HttpServletRequest request) {
        try {
            var pending = confirmations.consume(session, actor.id(), id, token);
            accounts.changeStatus(id, pending.version(), pending.status(), pending.reason(),
                actor.id(), actor.username(), RequestIdFilter.id(request));
            return "redirect:/accounts/" + id + "?updated";
        } catch (UserCommandException e) {
            denied(actor, request, "ACCOUNT_STATUS_DENIED", id, e);
            throw e;
        }
    }

    private void denied(AccountPrincipal actor, HttpServletRequest request, String action,
                        UUID id, UserCommandException e) {
        try {
            attempts.record(AuditEntry.denied(actor.id(), actor.username(), "BUSINESS", action, "ACCOUNT",
                id == null ? null : id.toString(), e.reasonCode(), RequestIdFilter.id(request)));
        } catch (Exception failure) {
            log.error("Account denial audit unavailable; requestId={}", RequestIdFilter.id(request), failure);
        }
    }
}
