package ru.tsu.accounts.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.tsu.accounts.account.AccountService;

@Controller
public class MyAccountsController {
    private final AccountService accounts;
    public MyAccountsController(AccountService accounts) { this.accounts = accounts; }
    @GetMapping("/my/accounts")
    @PreAuthorize("hasRole('CLIENT')")
    public String own(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("accounts", accounts.own(page));
        return "accounts/own";
    }
}
