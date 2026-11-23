package ru.tsu.accounts.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.tsu.accounts.security.AccountPrincipal;

@Controller
public class PagesController {
    private final ru.tsu.accounts.account.AccountService accounts;
    private final DashboardQuery dashboard;
    public PagesController(ru.tsu.accounts.account.AccountService accounts, DashboardQuery dashboard) {
        this.accounts = accounts; this.dashboard = dashboard;
    }
    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal AccountPrincipal user, Model model) {
        model.addAttribute("name", user.fullName());
        model.addAttribute("role", user.role());
        if ("CLIENT".equals(user.role())) model.addAttribute("ownAccounts", accounts.own(0));
        if ("ACCOUNTANT".equals(user.role())) model.addAttribute("recentDrafts", dashboard.ownDrafts());
        return "home";
    }
}
