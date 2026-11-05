package ru.tsu.accounts.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.tsu.accounts.security.AccountPrincipal;

@Controller
public class PagesController {
    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal AccountPrincipal user, Model model) {
        model.addAttribute("name", user.fullName());
        model.addAttribute("role", user.role());
        return "home";
    }
}
