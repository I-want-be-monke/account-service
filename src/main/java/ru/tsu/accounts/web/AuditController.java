package ru.tsu.accounts.web;

import java.time.LocalDate;
import java.util.UUID;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.tsu.accounts.audit.AuditQueryService;
import ru.tsu.accounts.audit.AuditSearch;
import ru.tsu.accounts.security.AccountPrincipal;

@Controller
@RequestMapping("/audit")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class AuditController {
    private static final Map<String,String> LABELS = Map.ofEntries(
        Map.entry("LOGIN", "Вход"), Map.entry("USER_CREATED", "Создание пользователя"),
        Map.entry("USER_UPDATED", "Изменение пользователя"), Map.entry("USER_ROLE_CHANGED", "Смена роли"),
        Map.entry("USER_STATUS_CHANGED", "Смена статуса"), Map.entry("USER_CLIENT_CHANGED", "Смена клиента"),
        Map.entry("ACCESS_DENIED", "Отказ в доступе"), Map.entry("SESSION_REVOKED", "Отзыв сеанса"),
        Map.entry("USER_CREATE_DENIED", "Отказ в создании"), Map.entry("USER_EDIT_DENIED", "Отказ в изменении"),
        Map.entry("USER_ACCESS_DENIED", "Отказ в смене доступа"), Map.entry("SUCCESS", "Успех"),
        Map.entry("DENIED", "Отказ"), Map.entry("SECURITY", "Безопасность"),
        Map.entry("ACCESS", "Доступ"), Map.entry("BUSINESS", "Действие"),
        Map.entry("OPERATIONS", "Эксплуатация"));
    private final AuditQueryService audits;
    public AuditController(AuditQueryService audits) { this.audits = audits; }

    @GetMapping
    public String list(@RequestParam(required = false) LocalDate from,
                       @RequestParam(required = false) LocalDate to,
                       @RequestParam(required = false) UUID actorId,
                       @RequestParam(defaultValue = "") String objectType,
                       @RequestParam(defaultValue = "") String objectId,
                       @RequestParam(defaultValue = "") String action,
                       @RequestParam(defaultValue = "") String result,
                       @RequestParam(defaultValue = "") String category,
                       @RequestParam(defaultValue = "0") int page,
                       @AuthenticationPrincipal AccountPrincipal user, Model model) {
        boolean admin = "ADMIN".equals(user.role());
        model.addAttribute("events", audits.search(new AuditSearch(from, to, actorId, objectType,
            objectId, action, result, category, page)));
        model.addAttribute("admin", admin);
        model.addAttribute("labels", LABELS);
        model.addAttribute("from", from); model.addAttribute("to", to);
        model.addAttribute("actorId", actorId); model.addAttribute("objectType", objectType);
        model.addAttribute("objectId", objectId); model.addAttribute("action", action);
        model.addAttribute("result", result); model.addAttribute("category", category);
        return "audit/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal user, Model model) {
        model.addAttribute("event", audits.get(id));
        model.addAttribute("labels", LABELS);
        return "audit/detail";
    }
}
