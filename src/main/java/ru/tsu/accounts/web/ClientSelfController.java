package ru.tsu.accounts.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.tsu.accounts.client.ClientService;
import ru.tsu.accounts.account.AccountService;

@Controller
public class ClientSelfController {
    private final ClientService clients;
    private final AccountService accounts;
    public ClientSelfController(ClientService clients, AccountService accounts) {
        this.clients = clients; this.accounts = accounts;
    }
    @GetMapping("/me/client")
    @PreAuthorize("hasRole('CLIENT')")
    public String own(Model model) {
        model.addAttribute("client", clients.getOwn());
        model.addAttribute("clientAccounts", accounts.own(0));
        return "clients/own";
    }
}
