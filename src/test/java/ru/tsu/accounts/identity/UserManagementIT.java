package ru.tsu.accounts.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditWriter;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditQueryService;
import ru.tsu.accounts.audit.AuditSearch;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class UserManagementIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm")
        .withDatabaseName("accounts").withUsername("account_owner")
        .withPassword("test-owner-password").withInitScript("db/test-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("account.demo.passwords.admin", () -> "correct-password-for-e1");
        registry.add("account.demo.passwords.operator", () -> "operator-password-for-e1");
        registry.add("account.demo.passwords.accountant", () -> "accountant-password-for-e1");
        registry.add("account.demo.passwords.manager", () -> "manager-password-for-e1");
        registry.add("account.demo.passwords.client", () -> "client-password-for-e1");
    }

    @Autowired UserManagementService users;
    @Autowired AppUserRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired DatabaseUserDetailsService details;
    @Autowired AuditQueryService auditQuery;
    @Autowired AuditAttemptService auditAttempts;
    @SpyBean AuditWriter writer;

    private UUID adminId() {
        return repository.findByLogin("demo-admin").orElseThrow().getId();
    }
    private String uniqueLogin() { return "test-" + UUID.randomUUID().toString().substring(0, 12); }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createsOperatorAndRejectsDuplicateOrClientWithoutLink() {
        String login = uniqueLogin();
        UserSummary created = users.create(login, "Учебный оператор", "strong-initial-password",
            "OPERATOR", null, adminId(), "demo-admin", UUID.randomUUID());
        assertThat(created.roleCode()).isEqualTo("OPERATOR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE login=?", Integer.class, login)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='USER_CREATED' AND object_id=?",
            Integer.class, created.id().toString())).isEqualTo(1);
        assertThatThrownBy(() -> users.create(login, "Дубль", "strong-initial-password", "OPERATOR",
            null, adminId(), "demo-admin", UUID.randomUUID())).isInstanceOf(UserCommandException.class);
        assertThatThrownBy(() -> users.create(uniqueLogin(), "Клиент", "strong-initial-password", "CLIENT",
            null, adminId(), "demo-admin", UUID.randomUUID())).isInstanceOf(UserCommandException.class);
        UUID clientId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO client(id,client_type,display_name,identifier_type,identifier_value,identifier_key)
            VALUES(?,'PERSON','Учебная карточка','DEMO',?,?)
            """, clientId, clientId.toString(), clientId.toString());
        var clientUser = users.create(uniqueLogin(), "Клиент", "strong-initial-password", "CLIENT",
            clientId, adminId(), "demo-admin", UUID.randomUUID());
        assertThat(clientUser.clientId()).isEqualTo(clientId);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleChangeRevokesOldPrincipalAndBlockingRequiresNewLogin() throws Exception {
        String login = uniqueLogin();
        var created = users.create(login, "Временный администратор", "strong-initial-password",
            "ADMIN", null, adminId(), "demo-admin", UUID.randomUUID());
        var old = new AccountPrincipal(created.id(), login, "hash", created.fullName(), "ADMIN", created.authVersion());
        var authenticated = new UsernamePasswordAuthenticationToken(old, null, old.getAuthorities());
        var changed = users.changeAccess(created.id(), "OPERATOR", "ACTIVE", null, created.version(),
            "Проверка смены роли", adminId(), "demo-admin", UUID.randomUUID());
        assertThat(changed.authVersion()).isGreaterThan(created.authVersion());
        mvc.perform(get("/admin/users").secure(true).with(authentication(authenticated)))
            .andExpect(status().is3xxRedirection());
        var adminPrincipal = details.loadUserByUsername("demo-admin");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            adminPrincipal, null, adminPrincipal.getAuthorities()));
        assertThat(jdbc.queryForObject("SELECT role_code FROM app_user WHERE id=?", String.class, created.id()))
            .isEqualTo("OPERATOR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='USER_ROLE_CHANGED' AND object_id=?",
            Integer.class, created.id().toString())).isEqualTo(1);
        var blocked = users.changeAccess(created.id(), "OPERATOR", "BLOCKED", null, changed.version(),
            "Проверка блокировки", adminId(), "demo-admin", UUID.randomUUID());
        assertThatThrownBy(() -> details.loadUserByUsername(login)).isInstanceOf(UsernameNotFoundException.class);
        var unblocked = users.changeAccess(created.id(), "OPERATOR", "ACTIVE", null, blocked.version(),
            "Проверка разблокировки", adminId(), "demo-admin", UUID.randomUUID());
        assertThat(details.loadUserByUsername(login).getUsername()).isEqualTo(login);
        assertThat(unblocked.authVersion()).isGreaterThan(blocked.authVersion());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void protectsLastAdminAndRollbackOnAuditFailure() {
        var admin = users.get(adminId());
        assertThatThrownBy(() -> users.changeAccess(admin.id(), "ADMIN", "BLOCKED", null,
            admin.version(), "Нельзя отключить", UUID.randomUUID(), "другой администратор", UUID.randomUUID()))
            .isInstanceOf(UserCommandException.class).hasMessageContaining("Последнего");

        var created = users.create(uniqueLogin(), "Проверка аудита", "strong-initial-password",
            "OPERATOR", null, adminId(), "demo-admin", UUID.randomUUID());
        doAnswer(invocation -> {
            AuditEntry event = invocation.getArgument(0);
            if ("USER_ROLE_CHANGED".equals(event.action()))
                throw new DataAccessResourceFailureException("simulated audit insert failure");
            return invocation.callRealMethod();
        }).when(writer).insert(any(AuditEntry.class));
        assertThatThrownBy(() -> users.changeAccess(created.id(), "ACCOUNTANT", "ACTIVE", null,
            created.version(), "Проверка отката", adminId(), "demo-admin", UUID.randomUUID()))
            .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("SELECT role_code FROM app_user WHERE id=?", String.class, created.id()))
            .isEqualTo("OPERATOR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='USER_ROLE_CHANGED' AND object_id=?",
            Integer.class, created.id().toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('account_runtime','audit_event','UPDATE')", Boolean.class))
            .isFalse();
    }

    @Test
    void httpRightsCsrfAndConfirmationGate() throws Exception {
        mvc.perform(get("/admin/users").secure(true).with(user("demo-operator").roles("OPERATOR")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/audit").secure(true).with(user("demo-operator").roles("OPERATOR")))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='ACCESS_DENIED' AND category='SECURITY'", Integer.class)).isGreaterThanOrEqualTo(2);
        mvc.perform(post("/admin/users/new").secure(true).with(user("demo-admin").roles("ADMIN"))
                .param("login", uniqueLogin()))
            .andExpect(status().isForbidden());
        var operator = repository.findByLogin("demo-operator").orElseThrow();
        var principal = (AccountPrincipal) details.loadUserByUsername("demo-admin");
        mvc.perform(post("/admin/users/" + operator.getId() + "/confirm").secure(true)
                .with(authentication(new UsernamePasswordAuthenticationToken(principal, null,
                    principal.getAuthorities()))).with(csrf()).param("token", "fabricated"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT role_code FROM app_user WHERE id=?", String.class,
            operator.getId())).isEqualTo("OPERATOR");
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managerSeesOnlyAllowedAuditCategories() {
        UUID request = UUID.randomUUID();
        auditAttempts.record(AuditEntry.denied(null, "unknown", "SECURITY", "LOGIN",
            "USER", null, "INVALID_CREDENTIALS", request));
        auditAttempts.record(AuditEntry.denied(null, "unknown", "ACCESS", "ACCESS_DENIED",
            "ACCOUNT", null, "ROLE_FORBIDDEN", UUID.randomUUID()));
        var visible = auditQuery.search(new AuditSearch(null, null, null, "", "", "", "", "", 0));
        assertThat(visible.rows()).allMatch(row -> !"SECURITY".equals(row.category()));
        var hiddenId = jdbc.queryForObject("SELECT id FROM audit_event WHERE request_id=?", Long.class, request);
        assertThatThrownBy(() -> auditQuery.get(hiddenId)).isInstanceOf(UserCommandException.class);
    }

    @Test
    void browserCreateAndConfirmedRoleChangeUsePostgresqlSession() throws Exception {
        Cookie session = loginAsAdmin();
        String login = uniqueLogin();
        mvc.perform(post("/admin/users/new").secure(true).cookie(session).with(csrf())
                .param("login", login).param("fullName", "Новый сотрудник")
                .param("password", "strong-initial-password").param("roleCode", "OPERATOR"))
            .andExpect(status().is3xxRedirection());
        var target = repository.findByLogin(login).orElseThrow();
        assertThat(target.getRole().getCode()).isEqualTo("OPERATOR");
        mvc.perform(post("/admin/users/" + target.getId() + "/prepare").secure(true)
                .cookie(session).with(csrf()).param("roleCode", "ACCOUNTANT")
                .param("status", "ACTIVE").param("reason", "Новая должность")
                .param("version", String.valueOf(target.getVersion())))
            .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT role_code FROM app_user WHERE id=?", String.class, target.getId()))
            .isEqualTo("OPERATOR");
        String html = mvc.perform(get("/admin/users/" + target.getId() + "/confirm")
                .secure(true).cookie(session)).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsString();
        var matcher = Pattern.compile("name=\"token\" value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        mvc.perform(post("/admin/users/" + target.getId() + "/confirm").secure(true)
                .cookie(session).with(csrf()).param("token", matcher.group(1)))
            .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT role_code FROM app_user WHERE id=?", String.class, target.getId()))
            .isEqualTo("ACCOUNTANT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='USER_ROLE_CHANGED' AND object_id=?",
            Integer.class, target.getId().toString())).isEqualTo(1);
    }

    private Cookie loginAsAdmin() throws Exception {
        var response = mvc.perform(post("/login").secure(true).with(csrf())
                .param("username", "demo-admin").param("password", "correct-password-for-e1"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).contains("ACCOUNT_SESSION=");
        return new Cookie("ACCOUNT_SESSION", setCookie.split("ACCOUNT_SESSION=", 2)[1].split(";", 2)[0]);
    }
}
