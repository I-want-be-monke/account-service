package ru.tsu.accounts.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.security.AccountPrincipal;

/** Read-only account selection; existing account routes enforce all permissions. */
@Controller
public class WorkspaceController {
    private final AccountService accounts;
    public WorkspaceController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/workspace/operations")
    @PreAuthorize("hasAnyRole('OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
    public String operations(@RequestParam(defaultValue="") String number,
        @RequestParam(defaultValue="0") int page, @AuthenticationPrincipal AccountPrincipal actor, Model model) {
        return select("operations", number, page, actor, model);
    }

    @GetMapping("/workspace/statements")
    @PreAuthorize("isAuthenticated()")
    public String statements(@RequestParam(defaultValue="") String number,
        @RequestParam(defaultValue="0") int page, @AuthenticationPrincipal AccountPrincipal actor, Model model) {
        return select("statements", number, page, actor, model);
    }

    private String select(String section, String number, int page, AccountPrincipal actor, Model model) {
        model.addAttribute("workspace", section);
        model.addAttribute("workspaceTitle", "operations".equals(section) ? "Финансовые операции" : "Выписки");
        model.addAttribute("accounts", "CLIENT".equals(actor.role()) ? accounts.own(page) : accounts.search(number, "", null, page));
        model.addAttribute("number", number);
        return "workspace";
    }
}
