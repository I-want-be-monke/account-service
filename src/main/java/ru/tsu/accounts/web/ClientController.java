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
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.client.ClientForm;
import ru.tsu.accounts.client.ClientService;
import ru.tsu.accounts.client.ClientStatusConfirmation;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@Controller
@RequestMapping("/clients")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER')")
public class ClientController {
    private static final Logger log = LoggerFactory.getLogger(ClientController.class);
    private final ClientService clients;
    private final ClientStatusConfirmation confirmations;
    private final AuditAttemptService attempts;
    public ClientController(ClientService clients, ClientStatusConfirmation confirmations,
                            AuditAttemptService attempts) {
        this.clients = clients; this.confirmations = confirmations; this.attempts = attempts;
    }
    @GetMapping
    public String list(@RequestParam(defaultValue = "") String name,
                       @RequestParam(defaultValue = "") String type,
                       @RequestParam(defaultValue = "") String identifier,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "") String accountNumber,
                       @RequestParam(defaultValue = "0") int page,
                       @AuthenticationPrincipal AccountPrincipal actor, Model model) {
        model.addAttribute("clients", clients.search(name, type, identifier, status, accountNumber, page));
        model.addAttribute("name", name); model.addAttribute("type", type);
        model.addAttribute("identifier", identifier); model.addAttribute("status", status);
        model.addAttribute("accountNumber", accountNumber);
        model.addAttribute("isOperator", actor.role().equals("OPERATOR"));
        return "clients/list";
    }
    @GetMapping("/new")
    @PreAuthorize("hasRole('OPERATOR')")
    public String newPage(Model model) {
        model.addAttribute("form", new ClientForm());
        return "clients/new";
    }
    @PostMapping("/new")
    @PreAuthorize("hasRole('OPERATOR')")
    public String create(@ModelAttribute("form") ClientForm form, BindingResult errors,
                         @AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request,
                         HttpServletResponse response, Model model) {
        if (errors.hasErrors()) {
            response.setStatus(400); model.addAttribute("formError", "Проверьте поля формы.");
            return "clients/new";
        }
        try {
            var client = clients.create(form, actor.id(), actor.username(), RequestIdFilter.id(request));
            return "redirect:/clients/" + client.id() + "?created";
        } catch (UserCommandException e) {
            response.setStatus(e.status().value()); model.addAttribute("formError", e.getMessage());
            denied(actor, request, "CLIENT_CREATE_DENIED", null, e);
            return "clients/new";
        }
    }
    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal actor, Model model) {
        model.addAttribute("client", clients.get(id));
        model.addAttribute("isOperator", actor.role().equals("OPERATOR"));
        return "clients/detail";
    }
    @GetMapping("/{id}/edit")
    @PreAuthorize("hasRole('OPERATOR')")
    public String editPage(@PathVariable UUID id, Model model) {
        var client = clients.get(id);
        model.addAttribute("client", client);
        model.addAttribute("form", ClientForm.from(client));
        return "clients/edit";
    }
    @PostMapping("/{id}/edit")
    @PreAuthorize("hasRole('OPERATOR')")
    public String edit(@PathVariable UUID id, @ModelAttribute("form") ClientForm form,
                       BindingResult errors, @AuthenticationPrincipal AccountPrincipal actor,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        if (errors.hasErrors()) {
            response.setStatus(400); return editError(id, form, model, "Проверьте поля формы.");
        }
        try {
            clients.edit(id, form, actor.id(), actor.username(), RequestIdFilter.id(request));
            return "redirect:/clients/" + id + "?updated";
        } catch (UserCommandException e) {
            response.setStatus(e.status().value());
            denied(actor, request, "CLIENT_EDIT_DENIED", id, e);
            return editError(id, form, model, e.getMessage());
        }
    }
    @GetMapping("/{id}/status")
    @PreAuthorize("hasRole('OPERATOR')")
    public String statusPage(@PathVariable UUID id, Model model) {
        model.addAttribute("client", clients.get(id));
        return "clients/status";
    }
    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('OPERATOR')")
    public String prepareStatus(@PathVariable UUID id, @RequestParam long version,
                                @RequestParam String status, @RequestParam String reason,
                                @AuthenticationPrincipal AccountPrincipal actor,
                                HttpServletRequest request, HttpServletResponse response, Model model) {
        try {
            clients.validateStatus(id, version, status, reason);
            confirmations.prepare(request.getSession(), actor.id(), id, version, status, reason);
            return "redirect:/clients/" + id + "/confirm";
        } catch (UserCommandException e) {
            response.setStatus(e.status().value()); model.addAttribute("formError", e.getMessage());
            model.addAttribute("client", clients.get(id));
            denied(actor, request, "CLIENT_STATUS_DENIED", id, e);
            return "clients/status";
        }
    }
    @GetMapping("/{id}/confirm")
    @PreAuthorize("hasRole('OPERATOR')")
    public String confirmPage(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal actor,
                              HttpSession session, Model model) {
        model.addAttribute("pending", confirmations.view(session, actor.id(), id));
        model.addAttribute("client", clients.get(id));
        return "clients/confirm";
    }
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('OPERATOR')")
    public String confirm(@PathVariable UUID id, @RequestParam String token,
                          @AuthenticationPrincipal AccountPrincipal actor, HttpSession session,
                          HttpServletRequest request) {
        try {
            var pending = confirmations.consume(session, actor.id(), id, token);
            clients.changeStatus(id, pending.version(), pending.status(), pending.reason(),
                actor.id(), actor.username(), RequestIdFilter.id(request));
            return "redirect:/clients/" + id + "?updated";
        } catch (UserCommandException e) {
            denied(actor, request, "CLIENT_STATUS_DENIED", id, e);
            throw e;
        }
    }
    private String editError(UUID id, ClientForm form, Model model, String message) {
        model.addAttribute("client", clients.get(id));
        model.addAttribute("form", form); model.addAttribute("formError", message);
        return "clients/edit";
    }
    private void denied(AccountPrincipal actor, HttpServletRequest request, String action,
                        UUID id, UserCommandException e) {
        try {
            attempts.record(AuditEntry.denied(actor.id(), actor.username(), "BUSINESS", action,
                "CLIENT", id == null ? null : id.toString(), e.reasonCode(), RequestIdFilter.id(request)));
        } catch (Exception failure) {
            log.error("Could not record denied client command; requestId={}", RequestIdFilter.id(request), failure);
        }
    }
}
