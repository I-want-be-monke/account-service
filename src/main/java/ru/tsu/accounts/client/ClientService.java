package ru.tsu.accounts.client;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditService;
import ru.tsu.accounts.account.AccountRepository;
import ru.tsu.accounts.identity.AppUserRepository;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@Service
public class ClientService {
    private static final Set<String> TYPES = Set.of("PERSON", "ORGANIZATION");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "ARCHIVED");
    private static final Pattern IDENTIFIER_TYPE = Pattern.compile("[A-Z][A-Z0-9_-]{0,31}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9() .-]{5,32}$");
    private final ClientRepository clients;
    private final AppUserRepository users;
    private final AuditService audit;
    private final AccountRepository accounts;

    public ClientService(ClientRepository clients, AppUserRepository users, AuditService audit,
                         AccountRepository accounts) {
        this.clients = clients; this.users = users; this.audit = audit; this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER')")
    public Page<ClientSummary> search(String name, String type, String identifier, String status, int page) {
        return search(name, type, identifier, status, "", page);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER')")
    public Page<ClientSummary> search(String name, String type, String identifier,
                                      String status, String accountNumber, int page) {
        String query = text(name);
        if (query.length() > 200 || query.contains("%") || query.contains("_"))
            throw bad("Некорректное имя для поиска.");
        String chosenType = text(type);
        String chosenStatus = text(status);
        if (!chosenType.isEmpty() && !TYPES.contains(chosenType)) throw bad("Неизвестный тип клиента.");
        if (!chosenStatus.isEmpty() && !STATUSES.contains(chosenStatus)) throw bad("Неизвестный статус.");
        if (page < 0 || page > 100000) throw bad("Некорректный номер страницы.");
        String number = text(accountNumber);
        if (number.length() > 32 || number.contains("%") || number.contains("_"))
            throw bad("Некорректный номер счета.");
        return clients.search(query.toLowerCase(java.util.Locale.ROOT), chosenType,
            key(text(identifier)), chosenStatus, number,
            PageRequest.of(page, 20, Sort.by(Sort.Direction.ASC, "displayName").and(Sort.by("id"))))
            .map(ClientSummary::from);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER')")
    public ClientSummary get(UUID id) { return ClientSummary.from(required(id)); }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('CLIENT')")
    public ClientSummary getOwn() {
        AccountPrincipal principal = (AccountPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        UUID id = users.findById(principal.id()).orElseThrow(() -> new UserCommandException(
            HttpStatus.FORBIDDEN, "CLIENT_ACCESS_DENIED", "Карточка недоступна.")).getClientId();
        if (id == null) throw new UserCommandException(HttpStatus.FORBIDDEN, "CLIENT_ACCESS_DENIED", "Карточка недоступна.");
        return ClientSummary.from(clients.findById(id).orElseThrow(() ->
            new UserCommandException(HttpStatus.FORBIDDEN, "CLIENT_ACCESS_DENIED", "Карточка недоступна.")));
    }

    @Transactional
    @PreAuthorize("hasRole('OPERATOR')")
    public ClientSummary create(ClientForm form, UUID actor, String login, UUID requestId) {
        Data data = validate(form);
        if (clients.findByClientTypeAndIdentifierTypeAndIdentifierKey(data.type(), data.kind(), data.key()).isPresent())
            throw conflict("Клиент с таким идентификатором уже существует.");
        Client client = clients.saveAndFlush(new Client(data.type(), data.name(), data.kind(), data.value(),
            data.key(), data.phone(), data.email()));
        audit.recordCriticalSuccess(event(actor, login, "CLIENT_CREATED", client, null, snapshot(client), null, requestId));
        return ClientSummary.from(client);
    }

    @Transactional
    @PreAuthorize("hasRole('OPERATOR')")
    public ClientSummary edit(UUID id, ClientForm form, UUID actor, String login, UUID requestId) {
        Client client = required(id);
        if (!Objects.equals(client.getVersion(), form.getVersion())) throw conflict("Карточка изменена. Обновите страницу.");
        if (!"ACTIVE".equals(client.getStatus())) throw conflict("Архивную карточку сначала восстановите.");
        Data data = validate(form);
        clients.findByClientTypeAndIdentifierTypeAndIdentifierKey(data.type(), data.kind(), data.key())
            .filter(other -> !other.getId().equals(id))
            .ifPresent(other -> { throw conflict("Клиент с таким идентификатором уже существует."); });
        Map<String, ?> before = snapshot(client);
        client.update(data.type(), data.name(), data.kind(), data.value(), data.key(), data.phone(), data.email());
        clients.flush();
        audit.recordCriticalSuccess(event(actor, login, "CLIENT_UPDATED", client, before, snapshot(client), null, requestId));
        return ClientSummary.from(client);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OPERATOR')")
    public ClientSummary validateStatus(UUID id, long version, String target, String reason) {
        Client client = required(id);
        if (!Objects.equals(client.getVersion(), version)) throw conflict("Карточка изменена. Обновите страницу.");
        if (target == null || !STATUSES.contains(target) || target.equals(client.getStatus())) throw bad("Выберите другой статус.");
        if (text(reason).isEmpty() || text(reason).length() > 500) throw bad("Укажите причину (до 500 символов).");
        if ("ARCHIVED".equals(target) && accounts.existsByClientIdAndStatusNot(id, "CLOSED"))
            throw conflict("Архивировать клиента можно только после закрытия всех счетов.");
        return ClientSummary.from(client);
    }

    @Transactional
    @PreAuthorize("hasRole('OPERATOR')")
    public ClientSummary changeStatus(UUID id, long version, String target, String reason,
                                      UUID actor, String login, UUID requestId) {
        Client client = clients.lockById(id).orElseThrow(() ->
            new UserCommandException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Клиент не найден."));
        if (!Objects.equals(client.getVersion(), version)) throw conflict("Карточка изменена. Обновите страницу.");
        if (target == null || !STATUSES.contains(target) || target.equals(client.getStatus()))
            throw bad("Выберите другой статус.");
        if (text(reason).isEmpty() || text(reason).length() > 500)
            throw bad("Укажите причину (до 500 символов).");
        if ("ARCHIVED".equals(target) && accounts.existsByClientIdAndStatusNot(id, "CLOSED"))
            throw conflict("Архивировать клиента можно только после закрытия всех счетов.");
        Map<String, ?> before = snapshot(client);
        client.changeStatus(target);
        clients.flush();
        audit.recordCriticalSuccess(event(actor, login, "CLIENT_STATUS_CHANGED", client, before,
            snapshot(client), Map.of("reason", text(reason)), requestId));
        return ClientSummary.from(client);
    }

    private Client required(UUID id) {
        return clients.findById(id).orElseThrow(() ->
            new UserCommandException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Клиент не найден."));
    }
    private Data validate(ClientForm form) {
        String type = text(form.getClientType()), name = text(form.getDisplayName());
        String kind = text(form.getIdentifierType()).toUpperCase(java.util.Locale.ROOT);
        String value = text(form.getIdentifierValue()), phone = text(form.getPhone()), email = text(form.getEmail());
        String normalized = key(value);
        if (!TYPES.contains(type) || name.isEmpty() || name.length() > 200 || hasControl(name))
            throw bad("Проверьте тип и наименование клиента (до 200 символов).");
        if (!IDENTIFIER_TYPE.matcher(kind).matches() || value.isEmpty() || value.length() > 100
            || normalized.isEmpty() || normalized.length() > 100 || hasControl(value))
            throw bad("Проверьте тип и значение идентификатора (до 100 символов).");
        if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) throw bad("Некорректный номер телефона.");
        if (!email.isEmpty() && (email.length() > 254 || !EMAIL.matcher(email).matches()))
            throw bad("Некорректный адрес электронной почты.");
        return new Data(type, name, kind, value, normalized, phone.isEmpty() ? null : phone,
            email.isEmpty() ? null : email);
    }
    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static String key(String value) { return value.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT); }
    private static boolean hasControl(String value) { return value.chars().anyMatch(Character::isISOControl); }
    private static Map<String, ?> snapshot(Client c) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("clientType", c.getClientType()); values.put("displayName", c.getDisplayName());
        values.put("identifierType", c.getIdentifierType()); values.put("identifierValue", c.getIdentifierValue());
        values.put("phone", c.getPhone()); values.put("email", c.getEmail()); values.put("status", c.getStatus());
        return values;
    }
    private static AuditEntry event(UUID actor, String login, String action, Client c, Map<String, ?> before,
                                    Map<String, ?> after, Map<String, ?> details, UUID requestId) {
        return new AuditEntry(actor, login, "USER", "BUSINESS", action, "CLIENT",
            c.getId().toString(), "SUCCESS", null, requestId, before, after, details);
    }
    private static UserCommandException bad(String message) {
        return new UserCommandException(HttpStatus.BAD_REQUEST, "CLIENT_VALIDATION", message);
    }
    private static UserCommandException conflict(String message) {
        return new UserCommandException(HttpStatus.CONFLICT, "CLIENT_CONFLICT", message);
    }
    private record Data(String type, String name, String kind, String value, String key, String phone, String email) { }
}
