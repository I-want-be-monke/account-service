package ru.tsu.accounts.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditWriter;
import ru.tsu.accounts.client.ClientForm;
import ru.tsu.accounts.client.ClientService;
import ru.tsu.accounts.client.ClientSummary;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class AccountLifecycleIT {
    @Container
    static final PostgreSQLContainer<?> postgres = ru.tsu.accounts.support.LocalPostgres.create()
        .withDatabaseName("accounts").withUsername("account_owner")
        .withPassword("test-owner-password").withInitScript("db/test-roles.sql");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("account.demo.passwords.admin", () -> "correct-password-for-e1");
        r.add("account.demo.passwords.operator", () -> "operator-password-for-e1");
        r.add("account.demo.passwords.accountant", () -> "accountant-password-for-e1");
        r.add("account.demo.passwords.manager", () -> "manager-password-for-e1");
        r.add("account.demo.passwords.client", () -> "client-password-for-e1");
    }
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepository;
    @Autowired ClientService clients;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired DatabaseUserDetailsService details;
    @SpyBean AuditWriter auditWriter;

    private static LocalDate today() { return LocalDate.now(ZoneId.of("Europe/Moscow")); }
    private AccountPrincipal principal(String login) {
        return (AccountPrincipal) details.loadUserByUsername(login);
    }
    private void as(String login) {
        var p = principal(login);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }
    private UsernamePasswordAuthenticationToken auth(String login) {
        var p = principal(login);
        return new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());
    }
    private ClientSummary client() {
        as("demo-operator");
        var f = new ClientForm();
        f.setClientType("PERSON"); f.setDisplayName("Счет учебного клиента");
        f.setIdentifierType("DEMO"); f.setIdentifierValue(UUID.randomUUID().toString());
        var actor = principal("demo-operator");
        return clients.create(f, actor.id(), actor.username(), UUID.randomUUID());
    }
    private AccountSummary open(UUID clientId) {
        as("demo-operator");
        var p = principal("demo-operator");
        return accounts.open(clientId, UUID.randomUUID(), today(), p.id(), p.username(), UUID.randomUUID());
    }

    @Test
    void twoAccountsGeneratedNumbersReplayAndDbInvariants() {
        var c = client();
        UUID openingId = UUID.randomUUID();
        var actor = principal("demo-operator");
        var first = accounts.open(c.id(), openingId, today(), actor.id(), actor.username(), UUID.randomUUID());
        var replay = accounts.open(c.id(), openingId, today(), actor.id(), actor.username(), UUID.randomUUID());
        var second = open(c.id());
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(first.accountNumber()).matches("LS-[0-9]{10}").isNotEqualTo(second.accountNumber());
        assertThat(first.currentBalance().toPlainString()).isEqualTo("0.00");
        assertThat(first.openingBalance().toPlainString()).isEqualTo("0.00");
        assertThat(jdbc.queryForObject("select count(*) from personal_account where client_id=?",
            Integer.class, c.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from account_status_history where account_id=?",
            Integer.class, first.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action='ACCOUNT_OPENED' and object_id=?",
            Integer.class, first.id().toString())).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into personal_account(id,account_number,client_id,status,opened_on,history_start_on) values (?,? ,?,'OPEN',?,?)",
            UUID.randomUUID(), first.accountNumber(), c.id(), today(), today()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update personal_account set client_id=? where id=?",
            UUID.randomUUID(), first.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> accounts.open(c.id(), openingId, today().minusDays(1),
            actor.id(), actor.username(), UUID.randomUUID())).isInstanceOf(UserCommandException.class);
        assertThat(jdbc.queryForObject("select count(*) from personal_account where id=?", Integer.class, openingId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
            .isEqualTo(5);
    }

    @Test
    void transitionsCloseAndArchiveGuardKeepHistory() {
        var c = client();
        var a = open(c.id());
        as("demo-operator");
        var actor = principal("demo-operator");
        assertThatThrownBy(() -> clients.changeStatus(c.id(), c.version(), "ARCHIVED",
            "Счёт ещё открыт", actor.id(), actor.username(), UUID.randomUUID()))
            .isInstanceOf(UserCommandException.class).hasMessageContaining("закрытия");
        assertThatThrownBy(() -> accounts.open(c.id(), UUID.randomUUID(), today().plusDays(1),
            actor.id(), actor.username(), UUID.randomUUID())).isInstanceOf(UserCommandException.class);
        var suspended = accounts.changeStatus(a.id(), a.version(), "SUSPENDED", "Проверка документов",
            actor.id(), actor.username(), UUID.randomUUID());
        var resumed = accounts.changeStatus(a.id(), suspended.version(), "OPEN", "Документы приняты",
            actor.id(), actor.username(), UUID.randomUUID());
        jdbc.update("update personal_account set current_balance=550.00 where id=?", a.id());
        assertThatThrownBy(() -> accounts.changeStatus(a.id(), resumed.version(), "CLOSED",
            "Закрытие", actor.id(), actor.username(), UUID.randomUUID()))
            .isInstanceOf(UserCommandException.class).hasMessageContaining("нулевом");
        jdbc.update("update personal_account set current_balance=0.00 where id=?", a.id());
        var closed = accounts.changeStatus(a.id(), resumed.version(), "CLOSED", "Закрытие",
            actor.id(), actor.username(), UUID.randomUUID());
        assertThat(closed.closedOn()).isEqualTo(today());
        assertThatThrownBy(() -> accounts.changeStatus(a.id(), closed.version(), "OPEN",
            "Повторное открытие", actor.id(), actor.username(), UUID.randomUUID()))
            .isInstanceOf(UserCommandException.class);
        assertThat(jdbc.queryForObject("select count(*) from account_status_history where account_id=?",
            Integer.class, a.id())).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where object_id=? and action='ACCOUNT_STATUS_CHANGED'",
            Integer.class, a.id().toString())).isEqualTo(3);
        var archived = clients.changeStatus(c.id(), c.version(), "ARCHIVED", "Все счета закрыты",
            actor.id(), actor.username(), UUID.randomUUID());
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThatThrownBy(() -> accounts.open(c.id(), UUID.randomUUID(), today(),
            actor.id(), actor.username(), UUID.randomUUID())).isInstanceOf(UserCommandException.class);
        assertThat(jdbc.queryForObject("select count(*) from personal_account where client_id=?",
            Integer.class, c.id())).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackAccountAndStatusHistory() {
        var c = client();
        var a = open(c.id());
        as("demo-operator");
        doAnswer(inv -> {
            AuditEntry event = inv.getArgument(0);
            if ("ACCOUNT_STATUS_CHANGED".equals(event.action()))
                throw new DataAccessResourceFailureException("simulated audit outage");
            return inv.callRealMethod();
        }).when(auditWriter).insert(any(AuditEntry.class));
        var actor = principal("demo-operator");
        assertThatThrownBy(() -> accounts.changeStatus(a.id(), a.version(), "SUSPENDED",
            "Сбой аудита", actor.id(), actor.username(), UUID.randomUUID()))
            .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("select status from personal_account where id=?", String.class, a.id()))
            .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select count(*) from account_status_history where account_id=?",
            Integer.class, a.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where object_id=? and action='ACCOUNT_STATUS_CHANGED'",
            Integer.class, a.id().toString())).isZero();
    }

    @Test
    void clientSeesOnlyOwnAccountStaffReadOnlyAndCsrf() throws Exception {
        var other = client();
        var foreign = open(other.id());
        var demo = jdbc.queryForObject("select client_id from app_user where login='demo-client'", UUID.class);
        var own = open(demo);
        mvc.perform(get("/my/accounts").secure(true).with(authentication(auth("demo-client"))))
            .andExpect(status().isOk());
        mvc.perform(get("/accounts/" + own.id()).secure(true).with(authentication(auth("demo-client"))))
            .andExpect(status().isOk());
        var denied = mvc.perform(get("/accounts/" + foreign.id()).secure(true)
            .with(authentication(auth("demo-client")))).andExpect(status().isForbidden())
            .andReturn().getResponse().getContentAsString();
        assertThat(denied).doesNotContain(foreign.accountNumber()).doesNotContain(other.displayName());
        mvc.perform(get("/accounts").secure(true).with(authentication(auth("demo-client"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/accounts").secure(true).with(authentication(auth("demo-admin"))))
            .andExpect(status().isOk());
        mvc.perform(get("/accounts/" + own.id() + "/status").secure(true)
            .with(authentication(auth("demo-admin")))).andExpect(status().isForbidden());
        mvc.perform(get("/clients/" + other.id() + "/accounts/new").secure(true)
            .with(authentication(auth("demo-accountant")))).andExpect(status().isForbidden());
        mvc.perform(post("/accounts/" + own.id() + "/status").secure(true)
            .with(authentication(auth("demo-operator"))).param("version", "0")
            .param("status", "SUSPENDED").param("reason", "Нет CSRF")).andExpect(status().isForbidden());
        mvc.perform(post("/accounts/" + own.id() + "/confirm").secure(true)
            .with(authentication(auth("demo-operator"))).with(csrf()).param("token", "invented"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select status from personal_account where id=?", String.class, own.id()))
            .isEqualTo("OPEN");
    }

    @Test
    void browserConfirmationAndSearchByAccountNumber() throws Exception {
        var c = client();
        var a = open(c.id());
        as("demo-operator");
        assertThat(clients.search("", "", "", "", a.accountNumber(), 0).getContent())
            .extracting(ClientSummary::id).contains(c.id());
        var response = mvc.perform(post("/login").secure(true).with(csrf())
            .param("username", "demo-operator").param("password", "operator-password-for-e1"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        var cookie = new Cookie("ACCOUNT_SESSION",
            response.getHeader("Set-Cookie").split("ACCOUNT_SESSION=", 2)[1].split(";", 2)[0]);
        mvc.perform(post("/accounts/" + a.id() + "/status").secure(true).cookie(cookie).with(csrf())
            .param("status", "SUSPENDED").param("version", Long.toString(a.version()))
            .param("reason", "Учебная проверка")).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select status from personal_account where id=?", String.class, a.id()))
            .isEqualTo("OPEN");
        String html = mvc.perform(get("/accounts/" + a.id() + "/confirm").secure(true).cookie(cookie))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var matcher = java.util.regex.Pattern.compile("name=\"token\" value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        mvc.perform(post("/accounts/" + a.id() + "/confirm").secure(true).cookie(cookie).with(csrf())
            .param("token", matcher.group(1))).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select status from personal_account where id=?", String.class, a.id()))
            .isEqualTo("SUSPENDED");
        mvc.perform(post("/accounts/" + a.id() + "/confirm").secure(true).cookie(cookie).with(csrf())
            .param("token", matcher.group(1))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from account_status_history where account_id=?",
            Integer.class, a.id())).isEqualTo(2);
    }

    @Test
    void concurrentReplayUsesOneAccountAndOneOpeningAudit() throws Exception {
        var c = client();
        var actor = principal("demo-operator");
        UUID openingId = UUID.randomUUID();
        var gate = new CountDownLatch(1);
        UUID first;
        UUID second;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                as("demo-operator"); gate.await(5, TimeUnit.SECONDS);
                try {
                    return accounts.open(c.id(), openingId, today(), actor.id(), actor.username(),
                        UUID.randomUUID()).id();
                } finally { SecurityContextHolder.clearContext(); }
            });
            var two = pool.submit(() -> {
                as("demo-operator"); gate.await(5, TimeUnit.SECONDS);
                try {
                    return accounts.open(c.id(), openingId, today(), actor.id(), actor.username(),
                        UUID.randomUUID()).id();
                } finally { SecurityContextHolder.clearContext(); }
            });
            gate.countDown();
            first = one.get(20, TimeUnit.SECONDS);
            second = two.get(20, TimeUnit.SECONDS);
        }
        assertThat(first).isEqualTo(second).isEqualTo(openingId);
        assertThat(jdbc.queryForObject("select count(*) from personal_account where id=?",
            Integer.class, openingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from account_status_history where account_id=?",
            Integer.class, openingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_event where object_id=? and action='ACCOUNT_OPENED'",
            Integer.class, openingId.toString())).isEqualTo(1);
    }

    @Test
    void concurrentOpenAndArchiveCannotLeaveArchivedClientWithOpenAccount() throws Exception {
        var c = client();
        var actor = principal("demo-operator");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var openTask = pool.submit(() -> {
                as("demo-operator"); gate.await(5, TimeUnit.SECONDS);
                try { accounts.open(c.id(), UUID.randomUUID(), today(), actor.id(), actor.username(), UUID.randomUUID()); }
                catch (UserCommandException ignored) { }
                finally { SecurityContextHolder.clearContext(); }
                return true;
            });
            var archiveTask = pool.submit(() -> {
                as("demo-operator"); gate.await(5, TimeUnit.SECONDS);
                try { clients.changeStatus(c.id(), c.version(), "ARCHIVED", "Гонка открытия",
                    actor.id(), actor.username(), UUID.randomUUID()); }
                catch (UserCommandException ignored) { }
                finally { SecurityContextHolder.clearContext(); }
                return true;
            });
            gate.countDown();
            assertThat(openTask.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(archiveTask.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from client c join personal_account a on a.client_id=c.id "
            + "where c.id=? and c.status='ARCHIVED' and a.status<>'CLOSED'", Integer.class, c.id())).isZero();
    }
}
