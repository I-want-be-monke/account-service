package ru.tsu.accounts.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.tsu.accounts.client.ClientService;

@Controller
public class ClientSelfController {
    private final ClientService clients;
    public ClientSelfController(ClientService clients) { this.clients = clients; }
    @GetMapping("/me/client")
    @PreAuthorize("hasRole('CLIENT')")
    public String own(Model model) {
        model.addAttribute("client", clients.getOwn());
        return "clients/own";
    }
}
