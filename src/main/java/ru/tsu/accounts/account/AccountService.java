package ru.tsu.accounts.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditService;
import ru.tsu.accounts.client.Client;
import ru.tsu.accounts.client.ClientRepository;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.operation.OperationRepository;

@Service
public class AccountService {
    private static final Set<String> STATUSES = Set.of("OPEN", "SUSPENDED", "CLOSED");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Moscow");
    private final AccountRepository accounts;
    private final AccountHistoryRepository history;
    private final ClientRepository clients;
    private final AccountAccessPolicy access;
    private final JdbcTemplate jdbc;
    private final OperationRepository operations;
    private final AuditService audit;

    public AccountService(AccountRepository accounts, AccountHistoryRepository history,
                          ClientRepository clients, AccountAccessPolicy access,
                          JdbcTemplate jdbc, AuditService audit, OperationRepository operations) {
        this.accounts = accounts; this.history = history; this.clients = clients;
        this.access = access; this.jdbc = jdbc; this.audit = audit; this.operations = operations;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER')")
    public Page<AccountSummary> search(String number, String status, UUID clientId, int page) {
        String chosen = number == null ? "" : number.strip();
        String state = status == null ? "" : status.strip();
        if (chosen.length() > 32 || chosen.contains("%") || chosen.contains("_"))
            throw bad("Некорректный номер счета.");
        if (!state.isEmpty() && !STATUSES.contains(state)) throw bad("Неизвестный статус счета.");
        return accounts.search(chosen, state, clientId, pageable(page)).map(this::summary);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('CLIENT')")
    public Page<AccountSummary> own(int page) {
        UUID id = access.ownClientId();
        return accounts.findByClientId(id, pageable(page)).map(this::summary);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public AccountSummary get(UUID id) { return summary(access.require(id)); }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Page<AccountStatusHistory> history(UUID id, int page) {
        access.require(id);
        return history.findByAccountId(id,
            PageRequest.of(checkedPage(page), 20, Sort.by("changedAt").ascending().and(Sort.by("id"))));
    }

    @Transactional
    @PreAuthorize("hasRole('OPERATOR')")
    public AccountSummary open(UUID clientId, UUID openingId, LocalDate date,
                               UUID actor, String login, UUID requestId) {
        if (clientId == null || openingId == null || date == null || date.isAfter(today()))
            throw bad("Проверьте клиента и дату открытия. Будущая дата запрещена.");
        // Every competing opening and client archive takes this lock first.
        Client client = clients.lockById(clientId).orElseThrow(() -> missingClient());
        PersonalAccount previous = accounts.findById(openingId).orElse(null);
        if (previous != null) {
            if (!previous.getClientId().equals(clientId) || !previous.getOpenedOn().equals(date))
                throw conflict("Идентификатор открытия уже использован с другими реквизитами.");
            return AccountSummary.from(previous, client.getDisplayName());
        }
        if (!"ACTIVE".equals(client.getStatus()))
            throw conflict("Архивному клиенту новый счет не открывается.");
        Long serial = jdbc.queryForObject("SELECT nextval('personal_account_number_seq')", Long.class);
        if (serial == null) throw new IllegalStateException("Account number sequence unavailable");
        String number = "LS-" + String.format(java.util.Locale.ROOT, "%010d", serial);
        PersonalAccount account = accounts.saveAndFlush(new PersonalAccount(openingId, number, clientId, date));
        history.saveAndFlush(new AccountStatusHistory(openingId, null, "OPEN", actor,
            "Открытие лицевого счета", requestId));
        audit.recordCriticalSuccess(event(actor, login, "ACCOUNT_OPENED", account, null,
            snapshot(account), Map.of("clientId", clientId.toString()), requestId));
        return AccountSummary.from(account, client.getDisplayName());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OPERATOR')")
    public AccountSummary validateStatus(UUID id, long version, String target, String reason) {
        PersonalAccount account = accounts.findById(id).orElseThrow(() -> missingAccount());
        checkStatus(account, version, target, reason);
        return summary(account);
    }

    @Transactional
    @PreAuthorize("hasRole('OPERATOR')")
    public AccountSummary changeStatus(UUID id, long version, String target, String reason,
                                       UUID actor, String login, UUID requestId) {
        PersonalAccount account = accounts.lockById(id).orElseThrow(() -> missingAccount());
        checkStatus(account, version, target, reason);
        String beforeStatus = account.getStatus();
        Map<String, ?> before = snapshot(account);
        account.changeStatus(target, today());
        accounts.flush();
        history.saveAndFlush(new AccountStatusHistory(id, beforeStatus, target, actor, reason.strip(), requestId));
        audit.recordCriticalSuccess(event(actor, login, "ACCOUNT_STATUS_CHANGED", account, before,
            snapshot(account), Map.of("reason", reason.strip()), requestId));
        return summary(account);
    }

    private void checkStatus(PersonalAccount account, long version, String target, String reason) {
        if (!Objects.equals(account.getVersion(), version))
            throw conflict("Счет изменен. Обновите страницу.");
        if (target == null || !STATUSES.contains(target)) throw bad("Неизвестный статус счета.");
        String current = account.getStatus();
        boolean allowed = ("OPEN".equals(current) && ("SUSPENDED".equals(target) || "CLOSED".equals(target)))
            || ("SUSPENDED".equals(current) && ("OPEN".equals(target) || "CLOSED".equals(target)));
        if (!allowed) throw conflict("Такой переход состояния запрещен.");
        if (reason == null || reason.strip().isEmpty() || reason.strip().length() > 500
            || reason.chars().anyMatch(Character::isISOControl))
            throw bad("Укажите причину изменения (до 500 символов).");
        if ("CLOSED".equals(target) && account.getCurrentBalance().compareTo(BigDecimal.ZERO) != 0)
            throw conflict("Закрыть счет можно только при нулевом сальдо.");
        if ("CLOSED".equals(target) && operations.existsByAccountIdAndStorageStatus(account.getId(), "PREPARED"))
            throw conflict("Сначала проведите или отбросьте все черновики по счету.");
    }

    private PageRequest pageable(int page) {
        return PageRequest.of(checkedPage(page), 20,
            Sort.by("openedOn").descending().and(Sort.by("id")));
    }
    private int checkedPage(int page) {
        if (page < 0 || page > 100000) throw bad("Некорректный номер страницы.");
        return page;
    }
    private AccountSummary summary(PersonalAccount account) {
        Client client = clients.findById(account.getClientId()).orElseThrow(() -> missingClient());
        return AccountSummary.from(account, client.getDisplayName());
    }
    private static LocalDate today() { return LocalDate.now(BUSINESS_ZONE); }
    private static Map<String, ?> snapshot(PersonalAccount a) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("number", a.getAccountNumber()); values.put("clientId", a.getClientId().toString());
        values.put("status", a.getStatus()); values.put("openedOn", a.getOpenedOn().toString());
        values.put("closedOn", a.getClosedOn() == null ? null : a.getClosedOn().toString());
        values.put("openingBalance", a.getOpeningBalance().toPlainString());
        values.put("currentBalance", a.getCurrentBalance().toPlainString());
        values.put("currency", a.getCurrency());
        return values;
    }
    private static AuditEntry event(UUID actor, String login, String action, PersonalAccount account,
                                    Map<String, ?> before, Map<String, ?> after,
                                    Map<String, ?> details, UUID requestId) {
        return new AuditEntry(actor, login, "USER", "BUSINESS", action, "ACCOUNT", account.getId().toString(),
            "SUCCESS", null, requestId, before, after, details);
    }
    private static UserCommandException missingAccount() {
        return new UserCommandException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Счет не найден.");
    }
    private static UserCommandException missingClient() {
        return new UserCommandException(HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND", "Клиент не найден.");
    }
    private static UserCommandException bad(String message) {
        return new UserCommandException(HttpStatus.BAD_REQUEST, "ACCOUNT_VALIDATION", message);
    }
    private static UserCommandException conflict(String message) {
        return new UserCommandException(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT", message);
    }
}
