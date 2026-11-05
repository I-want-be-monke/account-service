package ru.tsu.accounts.identity;

import java.util.Locale;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditService;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementService {
    private static final Set<String> ROLES = Set.of("ADMIN", "OPERATOR", "ACCOUNTANT", "MANAGER", "CLIENT");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "BLOCKED");
    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public UserManagementService(AppUserRepository users, AppRoleRepository roles,
                                 PasswordEncoder encoder, JdbcTemplate jdbc, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<UserSummary> search(String rawLogin, String role, String status, int page) {
        if (rawLogin != null && rawLogin.length() > 100) throw invalid("FILTER_INVALID", "Слишком длинный логин для поиска.");
        if (!role.isEmpty() && !ROLES.contains(role) || !status.isEmpty() && !STATUSES.contains(status))
            throw invalid("FILTER_INVALID", "Некорректный фильтр.");
        if (page < 0 || page > 100000) throw invalid("PAGE_INVALID", "Некорректная страница.");
        return users.search(rawLogin == null ? "" : normalize(rawLogin), role, status,
            PageRequest.of(page, 20, Sort.by(Sort.Direction.ASC, "login"))).map(UserSummary::from);
    }

    @Transactional(readOnly = true)
    public UserSummary get(UUID id) {
        return UserSummary.from(users.findById(id).orElseThrow(() ->
            new UserCommandException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Пользователь не найден.")));
    }

    @Transactional
    public UserSummary create(String rawLogin, String rawFullName, String password,
                              String roleCode, UUID clientId, UUID actorId, String actorLogin,
                              UUID requestId) {
        String login = validLogin(rawLogin);
        String fullName = validName(rawFullName);
        if (password == null || password.length() < 12 || password.length() > 128)
            throw invalid("PASSWORD_INVALID", "Пароль должен содержать от 12 до 128 символов.");
        AppRole role = validRole(roleCode);
        validateClient(roleCode, clientId);
        if (users.findByLogin(login).isPresent()) throw conflict("LOGIN_EXISTS", "Логин уже используется.");
        AppUser user = users.saveAndFlush(new AppUser(login, fullName, encoder.encode(password), role, clientId));
        audit.recordCriticalSuccess(AuditEntry.user(actorId, actorLogin, "USER_CREATED", user.getId(),
            null, fields(user), requestId));
        return UserSummary.from(user);
    }

    @Transactional
    public UserSummary editIdentity(UUID id, String rawLogin, String rawFullName, long version,
                                    UUID actorId, String actorLogin, UUID requestId) {
        AppUser user = required(id);
        checkVersion(user, version);
        String login = validLogin(rawLogin);
        String fullName = validName(rawFullName);
        if (users.findByLogin(login).filter(other -> !other.getId().equals(id)).isPresent())
            throw conflict("LOGIN_EXISTS", "Логин уже используется.");
        if (user.getLogin().equals(login) && user.getFullName().equals(fullName)) return UserSummary.from(user);
        Map<String, ?> before = fields(user);
        String oldLogin = user.getLogin();
        user.updateIdentity(login, fullName);
        users.flush();
        audit.recordCriticalSuccess(AuditEntry.user(actorId, actorLogin, "USER_UPDATED", id,
            before, fields(user), requestId));
        if (!oldLogin.equals(login)) revokeSessions(oldLogin);
        return UserSummary.from(user);
    }

    @Transactional(readOnly = true)
    public void validateAccess(UUID id, String roleCode, String status, UUID clientId, long version,
                               String reason, UUID actorId) {
        AppUser user = required(id);
        checkVersion(user, version);
        validRole(roleCode);
        validStatus(status);
        validateClient(roleCode, clientId);
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw invalid("REASON_INVALID", "Укажите причину изменения (до 500 символов).");
        if (user.getId().equals(actorId) && status.equals("BLOCKED"))
            throw conflict("SELF_BLOCK", "Нельзя заблокировать собственную учетную запись.");
        if (user.getRole().getCode().equals(roleCode) && user.getStatus().equals(status)
            && Objects.equals(user.getClientId(), clientId))
            throw invalid("NO_CHANGE", "Выберите изменение роли, статуса или привязки клиента.");
    }

    @Transactional
    public UserSummary changeAccess(UUID id, String roleCode, String status, UUID clientId,
                                    long version, String reason, UUID actorId, String actorLogin, UUID requestId) {
        AppUser user = required(id);
        checkVersion(user, version);
        AppRole role = validRole(roleCode);
        validStatus(status);
        validateClient(roleCode, clientId);
        if (user.getRole().getCode().equals(roleCode) && user.getStatus().equals(status)
            && Objects.equals(user.getClientId(), clientId))
            throw invalid("NO_CHANGE", "Изменение не выбрано.");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw invalid("REASON_INVALID", "Укажите причину изменения (до 500 символов).");
        if (id.equals(actorId) && "BLOCKED".equals(status))
            throw conflict("SELF_BLOCK", "Нельзя заблокировать собственную учетную запись.");
        boolean removingAdmin = "ADMIN".equals(user.getRole().getCode())
            && "ACTIVE".equals(user.getStatus()) && (!"ADMIN".equals(roleCode) || !"ACTIVE".equals(status));
        if (removingAdmin) {
            var adminIds = jdbc.query("""
                SELECT id FROM app_user WHERE role_code='ADMIN' AND status='ACTIVE' ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class));
            if (adminIds.size() < 2) throw conflict("LAST_ADMIN", "Последнего активного администратора нельзя отключить.");
        }
        Map<String, ?> before = fields(user);
        String oldLogin = user.getLogin();
        String action = !user.getRole().getCode().equals(roleCode) ? "USER_ROLE_CHANGED"
            : !user.getStatus().equals(status) ? "USER_STATUS_CHANGED" : "USER_CLIENT_CHANGED";
        user.changeAccess(role, status, clientId);
        users.flush();
        audit.recordCriticalSuccess(AuditEntry.userWithReason(actorId, actorLogin, action, id,
            before, fields(user), reason.strip(), requestId));
        revokeSessions(oldLogin);
        return UserSummary.from(user);
    }

    private AppUser required(UUID id) {
        return users.findById(id).orElseThrow(() ->
            new UserCommandException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Пользователь не найден."));
    }

    private void checkVersion(AppUser user, long version) {
        if (!Objects.equals(user.getVersion(), version))
            throw conflict("STALE_USER", "Данные изменились. Откройте карточку заново.");
    }

    private String normalize(String value) { return value.strip().toLowerCase(Locale.ROOT); }

    private String validLogin(String raw) {
        String login = raw == null ? "" : normalize(raw);
        if (!login.matches("[a-z0-9][a-z0-9._-]{2,99}"))
            throw invalid("LOGIN_INVALID", "Логин: 3–100 латинских букв, цифр, точки, дефиса или подчеркивания.");
        return login;
    }

    private String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 200)
            throw invalid("NAME_INVALID", "Имя должно содержать от 1 до 200 символов.");
        return name;
    }

    private AppRole validRole(String roleCode) {
        if (roleCode == null || !ROLES.contains(roleCode))
            throw invalid("ROLE_INVALID", "Выберите одну из пяти ролей.");
        return roles.findById(roleCode).orElseThrow(() -> invalid("ROLE_INVALID", "Роль не найдена."));
    }

    private void validStatus(String status) {
        if (status == null || !STATUSES.contains(status)) throw invalid("STATUS_INVALID", "Некорректный статус.");
    }

    private void validateClient(String role, UUID clientId) {
        if (!"CLIENT".equals(role)) {
            if (clientId != null) throw invalid("CLIENT_LINK_INVALID", "Привязка клиента допустима только для роли CLIENT.");
            return;
        }
        if (clientId == null) throw invalid("CLIENT_REQUIRED", "Укажите существующий ID клиента.");
        Integer count = jdbc.queryForObject("SELECT count(*) FROM client WHERE id=? AND status='ACTIVE'",
            Integer.class, clientId);
        if (count == null || count == 0) throw invalid("CLIENT_NOT_FOUND", "Активный клиент не найден.");
    }

    private Map<String, ?> fields(AppUser u) {
        return Map.of("login", u.getLogin(), "fullName", u.getFullName(), "role", u.getRole().getCode(),
            "status", u.getStatus(), "clientId", u.getClientId() == null ? "" : u.getClientId().toString());
    }

    private void revokeSessions(String login) {
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", login);
    }

    private UserCommandException invalid(String code, String message) {
        return new UserCommandException(HttpStatus.BAD_REQUEST, code, message);
    }
    private UserCommandException conflict(String code, String message) {
        return new UserCommandException(HttpStatus.CONFLICT, code, message);
    }
}
