package ru.tsu.accounts.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
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
import ru.tsu.accounts.identity.AppUserRepository;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class ClientServiceIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm")
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
    @Autowired ClientService clients;
    @Autowired ClientRepository repository;
    @Autowired AppUserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired DatabaseUserDetailsService userDetails;
    @SpyBean AuditWriter auditWriter;

    private AccountPrincipal principal(String login) {
        return (AccountPrincipal) userDetails.loadUserByUsername(login);
    }
    private void as(String login) {
        var p = principal(login);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }
    private ClientForm form(String type, String value) {
        var f = new ClientForm();
        f.setClientType(type);
        f.setDisplayName("Учебный клиент " + value);
        f.setIdentifierType("DEMO");
        f.setIdentifierValue(value);
        f.setEmail("demo@example.org");
        f.setPhone("+7 (999) 123-45-67");
        return f;
    }
    private ClientSummary create() {
        as("demo-operator");
        return clients.create(form("PERSON", UUID.randomUUID().toString()), principal("demo-operator").id(),
            "demo-operator", UUID.randomUUID());
    }
    private UsernamePasswordAuthenticationToken auth(String login) {
        var p = principal(login);
        return new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());
    }
    @Test
    void createsBothTypesNormalizesKeysAndWritesAudit() {
        as("demo-operator");
        var operator = principal("demo-operator");
        String key = UUID.randomUUID().toString();
        var person = clients.create(form("PERSON", "  " + key + "  "), operator.id(), operator.username(), UUID.randomUUID());
        var company = clients.create(form("ORGANIZATION", key), operator.id(), operator.username(), UUID.randomUUID());
        var anotherForm = form("PERSON", UUID.randomUUID().toString());
        anotherForm.setDisplayName(person.displayName());
        var sameName = clients.create(anotherForm, operator.id(), operator.username(), UUID.randomUUID());
        assertThat(sameName.id()).isNotEqualTo(person.id());
        assertThat(person.clientType()).isEqualTo("PERSON");
        assertThat(company.clientType()).isEqualTo("ORGANIZATION");
        assertThat(jdbc.queryForObject("select count(*) from client where id in (?,?)",
            Integer.class, person.id(), company.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select identifier_key from client where id=?", String.class, person.id()))
            .isEqualTo(key.toUpperCase());
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action='CLIENT_CREATED' and object_id=?",
            Integer.class, person.id().toString())).isEqualTo(1);
        assertThatThrownBy(() -> clients.create(form("PERSON", key.toUpperCase()),
            operator.id(), operator.username(), UUID.randomUUID()))
            .isInstanceOf(UserCommandException.class).hasMessageContaining("существует");
    }
    @Test
    void rejectsBadContactAndStaleVersionWithoutDatabaseMutation() {
        var c = create();
        as("demo-operator");
        var invalid = form("PERSON", c.identifierValue());
        invalid.setEmail("wrong-email");
        invalid.setVersion(c.version());
        assertThatThrownBy(() -> clients.edit(c.id(), invalid, principal("demo-operator").id(),
            "demo-operator", UUID.randomUUID())).isInstanceOf(UserCommandException.class);
        var changed = form("PERSON", c.identifierValue());
        changed.setDisplayName("Изменённое имя");
        changed.setVersion(c.version());
        var updated = clients.edit(c.id(), changed, principal("demo-operator").id(),
            "demo-operator", UUID.randomUUID());
        assertThat(updated.version()).isGreaterThan(c.version());
        assertThatThrownBy(() -> clients.edit(c.id(), changed, principal("demo-operator").id(),
            "demo-operator", UUID.randomUUID())).isInstanceOf(UserCommandException.class);
        assertThat(jdbc.queryForObject("select display_name from client where id=?", String.class, c.id()))
            .isEqualTo("Изменённое имя");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action='CLIENT_UPDATED' and object_id=?",
            Integer.class, c.id().toString())).isEqualTo(1);
    }
    @Test
    void mandatoryAuditFailureRollsBackStatus() {
        var c = create();
        as("demo-operator");
        doAnswer(invocation -> {
            AuditEntry e = invocation.getArgument(0);
            if ("CLIENT_STATUS_CHANGED".equals(e.action()))
                throw new DataAccessResourceFailureException("simulated audit failure");
            return invocation.callRealMethod();
        }).when(auditWriter).insert(any(AuditEntry.class));
        assertThatThrownBy(() -> clients.changeStatus(c.id(), c.version(), "ARCHIVED", "Проверка отката",
            principal("demo-operator").id(), "demo-operator", UUID.randomUUID()))
            .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("select status from client where id=?", String.class, c.id()))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action='CLIENT_STATUS_CHANGED' and object_id=?",
            Integer.class, c.id().toString())).isZero();
    }
    @Test
    void staffReadOnlyClientIsolationCsrfAndConfirmation() throws Exception {
        var c = create();
        mvc.perform(get("/clients").secure(true).with(authentication(auth("demo-accountant"))))
            .andExpect(status().isOk());
        mvc.perform(get("/clients/new").secure(true).with(authentication(auth("demo-accountant"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/clients").secure(true).with(authentication(auth("demo-client"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/clients/" + c.id()).secure(true).with(authentication(auth("demo-client"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/me/client").secure(true).with(authentication(auth("demo-client"))))
            .andExpect(status().isOk());
        mvc.perform(post("/clients/new").secure(true).with(authentication(auth("demo-operator")))
            .param("clientType", "PERSON").param("displayName", "Новый")).andExpect(status().isForbidden());
        mvc.perform(post("/clients/" + c.id() + "/confirm").secure(true)
            .with(authentication(auth("demo-operator"))).with(csrf()).param("token", "invented"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select status from client where id=?", String.class, c.id()))
            .isEqualTo("ACTIVE");
    }
    @Test
    void searchAndRealConfirmationPersistAndAudit() throws Exception {
        String unique = UUID.randomUUID().toString();
        as("demo-operator");
        var c = clients.create(form("ORGANIZATION", unique), principal("demo-operator").id(),
            "demo-operator", UUID.randomUUID());
        assertThat(clients.search("Учебный клиент", "ORGANIZATION", unique.toLowerCase(),
            "ACTIVE", 0).getContent()).extracting(ClientSummary::id).contains(c.id());
        assertThat(clients.search("Несуществующий", "", "", "", 0).getContent())
            .extracting(ClientSummary::id).doesNotContain(c.id());
        mvc.perform(get("/clients/" + c.id()).secure(true).with(authentication(auth("demo-operator"))))
            .andExpect(status().isOk());
        mvc.perform(get("/clients/" + c.id() + "/edit").secure(true).with(authentication(auth("demo-operator"))))
            .andExpect(status().isOk());
        mvc.perform(get("/clients/" + c.id() + "/status").secure(true).with(authentication(auth("demo-operator"))))
            .andExpect(status().isOk());
        var loggedIn = mvc.perform(post("/login").secure(true).with(csrf())
                .param("username", "demo-operator").param("password", "operator-password-for-e1"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        var cookie = new jakarta.servlet.http.Cookie("ACCOUNT_SESSION",
            loggedIn.getHeader("Set-Cookie").split("ACCOUNT_SESSION=", 2)[1].split(";", 2)[0]);
        mvc.perform(post("/clients/" + c.id() + "/status").secure(true).cookie(cookie).with(csrf())
                .param("version", Long.toString(c.version())).param("status", "ARCHIVED")
                .param("reason", "Учебное архивирование"))
            .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select status from client where id=?", String.class, c.id()))
            .isEqualTo("ACTIVE");
        String html = mvc.perform(get("/clients/" + c.id() + "/confirm").secure(true).cookie(cookie))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var matcher = java.util.regex.Pattern.compile("name=\"token\" value=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        mvc.perform(post("/clients/" + c.id() + "/confirm").secure(true).cookie(cookie).with(csrf())
                .param("token", matcher.group(1))).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("select status from client where id=?", String.class, c.id()))
            .isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("select count(*) from audit_event where action='CLIENT_STATUS_CHANGED' and object_id=?",
            Integer.class, c.id().toString())).isEqualTo(1);
    }
}
