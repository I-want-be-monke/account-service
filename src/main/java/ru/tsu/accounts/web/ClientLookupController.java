package ru.tsu.accounts.web;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tsu.accounts.client.ClientService;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER')")
public class ClientLookupController {
    private final ClientService clients;

    public ClientLookupController(ClientService clients) { this.clients = clients; }

    @GetMapping("/ui/clients/search")
    public List<ClientOption> search(@RequestParam(defaultValue = "") String q,
                                     @RequestParam(defaultValue = "false") boolean activeOnly,
                                     HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        if (q.isBlank()) return List.of();
        return clients.search(q, "", "", activeOnly ? "ACTIVE" : "", 0).getContent().stream()
            .map(client -> new ClientOption(client.id(), client.displayName(),
                client.identifierType() + " " + client.identifierValue()))
            .toList();
    }

    public record ClientOption(UUID id, String name, String identifier) {}
}
