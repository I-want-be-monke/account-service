package ru.tsu.accounts.web;

import java.util.UUID;
import java.util.stream.Collectors;
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
import ru.tsu.accounts.identity.AccessChangeForm;
import ru.tsu.accounts.identity.AppRoleRepository;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.identity.UserCreateForm;
import ru.tsu.accounts.identity.UserEditForm;
import ru.tsu.accounts.identity.UserManagementService;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.ConfirmationGuard;

@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    private static final Logger log = LoggerFactory.getLogger(UserAdminController.class);
    private final UserManagementService users;
    private final AppRoleRepository roles;
    private final ConfirmationGuard confirmations;
    private final AuditAttemptService attempts;
    private final ClientPickerModel clientPicker;

    public UserAdminController(UserManagementService users, AppRoleRepository roles,
                               ConfirmationGuard confirmations, AuditAttemptService attempts,
                               ClientPickerModel clientPicker) {
        this.users = users; this.roles = roles; this.confirmations = confirmations; this.attempts = attempts;
        this.clientPicker = clientPicker;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String login,
                       @RequestParam(defaultValue = "") String role,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("users", users.search(login, role, status, page));
        model.addAttribute("login", login); model.addAttribute("role", role);
        model.addAttribute("status", status); model.addAttribute("roles", roles.findAll());
        return "users/list";
    }

    @GetMapping("/new")
    public String createPage(@RequestParam(required = false) UUID clientId,
                             @RequestParam(defaultValue = "") String clientQuery,
                             @RequestParam(defaultValue = "false") boolean searchClients, Model model) {
        UserCreateForm form = new UserCreateForm();
        form.setClientId(clientId);
        if (clientId != null) form.setRoleCode("CLIENT");
        model.addAttribute("form", form);
        model.addAttribute("roles", roles.findAll());
        clientPicker.populate(clientId, clientQuery, searchClients, "ACTIVE", model);
        return "users/new";
    }

    @PostMapping("/new")
    public String create(@ModelAttribute("form") UserCreateForm form, BindingResult errors,
                         @RequestParam(defaultValue = "") String clientQuery,
                         @AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request,
                         HttpServletResponse response, Model model) {
        if (errors.hasErrors()) {
            response.setStatus(400);
            model.addAttribute("roles", roles.findAll());
            clientPicker.populate(form.getClientId(), clientQuery, false, "ACTIVE", model);
            return "users/new";
        }
        try {
            users.create(form.getLogin(), form.getFullName(), form.getPassword(), form.getRoleCode(),
                form.getClientId(), actor.id(), actor.username(), RequestIdFilter.id(request));
            return "redirect:/admin/users?created";
        } catch (UserCommandException e) {
            response.setStatus(e.status().value());
            denied(actor, request, "USER_CREATE_DENIED", null, e);
            model.addAttribute("roles", roles.findAll()); model.addAttribute("formError", e.getMessage());
            clientPicker.populate(form.getClientId(), clientQuery, false, "ACTIVE", model);
            return "users/new";
        }
    }

    @GetMapping("/{id}/edit")
    public String editPage(@PathVariable UUID id, @RequestParam(required = false) UUID clientId,
                           @RequestParam(defaultValue = "") String clientQuery,
                           @RequestParam(defaultValue = "false") boolean clearClient,
                           @RequestParam(defaultValue = "false") boolean searchClients, Model model) {
        var user = users.get(id);
        model.addAttribute("user", user);
        model.addAttribute("form", UserEditForm.from(user));
        AccessChangeForm accessForm = AccessChangeForm.from(user);
        if (clearClient) {
            accessForm.setClientId(null);
        } else if (clientId != null) {
            accessForm.setClientId(clientId);
            accessForm.setRoleCode("CLIENT");
        }
        model.addAttribute("accessForm", accessForm);
        model.addAttribute("roles", roles.findAll());
        clientPicker.populate(accessForm.getClientId(), clientQuery, searchClients, "ACTIVE", model);
        return "users/edit";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable UUID id, @ModelAttribute("form") UserEditForm form,
                       BindingResult errors, @AuthenticationPrincipal AccountPrincipal actor,
                       HttpServletRequest request, HttpServletResponse response, Model model) {
        if (errors.hasErrors()) {
            response.setStatus(400);
            return editWithError(id, model, form, "Проверьте поля формы.");
        }
        try {
            users.editIdentity(id, form.getLogin(), form.getFullName(), form.getVersion(),
                actor.id(), actor.username(), RequestIdFilter.id(request));
            return "redirect:/admin/users/" + id + "/edit?updated";
        } catch (UserCommandException e) {
            response.setStatus(e.status().value());
            denied(actor, request, "USER_EDIT_DENIED", id, e);
            return editWithError(id, model, form, e.getMessage());
        }
    }

    @PostMapping("/{id}/prepare")
    public String prepare(@PathVariable UUID id, @ModelAttribute("accessForm") AccessChangeForm form,
                          BindingResult errors, @RequestParam(defaultValue = "") String clientQuery,
                          @AuthenticationPrincipal AccountPrincipal actor,
                          HttpServletRequest request, HttpServletResponse response, Model model) {
        if (errors.hasErrors()) {
            response.setStatus(400);
            return accessWithError(id, model, form, "Укажите роль, статус и причину.", clientQuery);
        }
        try {
            users.validateAccess(id, form.getRoleCode(), form.getStatus(), form.getClientId(),
                form.getVersion(), form.getReason(), actor.id());
            confirmations.prepare(request.getSession(), actor.id(), id, form);
            return "redirect:/admin/users/" + id + "/confirm";
        } catch (UserCommandException e) {
            response.setStatus(e.status().value());
            denied(actor, request, "USER_ACCESS_DENIED", id, e);
            return accessWithError(id, model, form, e.getMessage(), clientQuery);
        }
    }

    @GetMapping("/{id}/confirm")
    public String confirmPage(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal actor,
                              HttpSession session, Model model) {
        var pending = confirmations.view(session, actor.id(), id);
        model.addAttribute("pending", pending);
        clientPicker.populate(pending.clientId(), "", false, "ACTIVE", model);
        model.addAttribute("user", users.get(id));
        model.addAttribute("roleNames", roles.findAll().stream().collect(
            Collectors.toMap(r -> r.getCode(), r -> r.getDisplayName())));
        return "users/confirm";
    }

    @PostMapping("/{id}/confirm")
    public String confirm(@PathVariable UUID id, @RequestParam String token,
                          @AuthenticationPrincipal AccountPrincipal actor, HttpSession session,
                          HttpServletRequest request) {
        try {
            var pending = confirmations.consume(session, actor.id(), id, token);
            users.changeAccess(id, pending.roleCode(), pending.status(), pending.clientId(),
                pending.version(), pending.reason(), actor.id(), actor.username(), RequestIdFilter.id(request));
            return "redirect:/admin/users?updated";
        } catch (UserCommandException e) {
            denied(actor, request, "USER_ACCESS_DENIED", id, e);
            throw e;
        }
    }

    private String editWithError(UUID id, Model model, UserEditForm form, String message) {
        var user = users.get(id);
        model.addAttribute("user", user); model.addAttribute("form", form);
        model.addAttribute("accessForm", AccessChangeForm.from(user));
        model.addAttribute("roles", roles.findAll()); model.addAttribute("formError", message);
        clientPicker.populate(user.clientId(), "", false, "ACTIVE", model);
        return "users/edit";
    }

    private String accessWithError(UUID id, Model model, AccessChangeForm form, String message, String clientQuery) {
        var user = users.get(id);
        model.addAttribute("user", user); model.addAttribute("form", UserEditForm.from(user));
        model.addAttribute("accessForm", form); model.addAttribute("roles", roles.findAll());
        model.addAttribute("accessError", message);
        clientPicker.populate(form.getClientId(), clientQuery, false, "ACTIVE", model);
        return "users/edit";
    }

    private void denied(AccountPrincipal actor, HttpServletRequest request, String action, UUID id,
                        UserCommandException e) {
        try {
            attempts.record(AuditEntry.denied(actor.id(), actor.username(), "SECURITY", action,
                "USER", id == null ? null : id.toString(), e.reasonCode(), RequestIdFilter.id(request)));
        } catch (Exception auditFailure) {
            log.error("Could not record denied user command; requestId={}", RequestIdFilter.id(request), auditFailure);
        }
    }
}
