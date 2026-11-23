package ru.tsu.accounts.web;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import ru.tsu.accounts.client.Client;
import ru.tsu.accounts.client.ClientRepository;
import ru.tsu.accounts.client.ClientService;

/** Read-only presentation data for the bounded client selector and its server fallback. */
@Component
public class ClientPickerModel {
    private final ClientRepository records;
    private final ClientService clients;

    public ClientPickerModel(ClientRepository records, ClientService clients) {
        this.records = records;
        this.clients = clients;
    }

    public void populate(UUID selectedId, String query, boolean search, String status, Model model) {
        model.addAttribute("selectedClientName", selectedId == null ? query
            : records.findById(selectedId).map(Client::getDisplayName).orElse("Клиент недоступен"));
        model.addAttribute("clientQuery", query);
        model.addAttribute("clientSearchRequested", search);
        model.addAttribute("clientOptions", search && !query.isBlank()
            ? clients.search(query, "", "", status, 0).getContent() : List.of());
    }
}
