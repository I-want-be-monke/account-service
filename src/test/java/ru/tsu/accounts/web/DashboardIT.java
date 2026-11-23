package ru.tsu.accounts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.account.AccountSummary;
import ru.tsu.accounts.client.ClientForm;
import ru.tsu.accounts.client.ClientService;
import ru.tsu.accounts.identity.UserManagementService;
import ru.tsu.accounts.identity.AccessChangeForm;
import ru.tsu.accounts.operation.OperationForm;
import ru.tsu.accounts.operation.OperationPostingService;
import ru.tsu.accounts.operation.OperationService;
import ru.tsu.accounts.operation.OperationSummary;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;
import ru.tsu.accounts.support.LocalPostgres;
import ru.tsu.accounts.support.PostgresContextLifecycle;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class DashboardIT extends PostgresContextLifecycle {
    @Container
    static final PostgreSQLContainer<?> postgres = LocalPostgres.create()
        .withDatabaseName("accounts").withUsername("account_owner")
        .withPassword("test-owner-password").withInitScript("db/test-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        for (String role : List.of("admin", "operator", "accountant", "manager", "client")) {
            registry.add("account.demo.passwords." + role, () -> "dashboard-test-password-" + role);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseUserDetailsService principals;
    @Autowired ClientService clients;
    @Autowired AccountService accounts;
    @Autowired UserManagementService users;
    @Autowired OperationService operations;
    @Autowired OperationPostingService posting;

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void linkedClientWithoutAccountsGetsEmptyHomeAndCannotReadAnotherClientsAccount() throws Exception {
        AccountSummary foreign = open(newClient());
        UUID client = newClient();
        as("demo-admin");
        AccountPrincipal admin = principal("demo-admin");
        String login = "empty-client-" + UUID.randomUUID();
        users.create(login, "Клиент без счетов", "dashboard-empty-client-password", "CLIENT", client,
            admin.id(), admin.username(), UUID.randomUUID());

        MvcResult home = page("/", login);
        assertThat(home.getModelAndView().getViewName()).isEqualTo("home");
        assertThat(accountPage(home, "ownAccounts")).isEmpty();
        assertThat(home.getResponse().getContentAsString()).doesNotContain(foreign.accountNumber());
        mvc.perform(get("/accounts/" + foreign.id()).secure(true).with(authentication(auth(login))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/" + foreign.id()).secure(true).with(authentication(auth(login))))
            .andExpect(status().isForbidden());
    }

    @Test
    void accountantDashboardShowsOnlyFiveOwnPreparedDraftsInCreationOrder() throws Exception {
        AccountSummary account = open(newClient());
        String owner = newAccountant();
        String other = newAccountant();
        OperationSummary posted = draft(owner, account.id(), 0);
        as(owner);
        AccountPrincipal actor = principal(owner);
        posting.post(posted.id(), posted.version(), UUID.randomUUID(), operations.readyFingerprint(posted.id()),
            actor.id(), actor.username(), UUID.randomUUID());
        List<UUID> prepared = new ArrayList<>();
        for (int index = 1; index <= 7; index++) prepared.add(draft(owner, account.id(), index).id());
        OperationSummary foreign = draft(other, account.id(), 8);

        List<UUID> expected = jdbc.query("""
            SELECT id FROM financial_operation WHERE created_by=? AND storage_status='PREPARED'
            ORDER BY created_at DESC,id DESC LIMIT 5
            """, (rs, row) -> rs.getObject(1, UUID.class), principal(owner).id());
        MvcResult home = page("/", owner);
        @SuppressWarnings("unchecked")
        List<DashboardQuery.Draft> visible = (List<DashboardQuery.Draft>) home.getModelAndView().getModel().get("recentDrafts");
        assertThat(visible).hasSize(5).extracting(item -> item.operation().id()).containsExactlyElementsOf(expected);
        assertThat(visible).extracting(item -> item.operation().id())
            .doesNotContain(posted.id(), foreign.id()).allMatch(prepared::contains);
        assertThat(home.getResponse().getContentAsString()).doesNotContain(foreign.id().toString(), posted.id().toString());
        mvc.perform(get("/operations/" + foreign.id()).secure(true).with(authentication(auth(owner))))
            .andExpect(status().isForbidden());
    }

    @Test
    void allFiveRolesGetMatchingNavigationAndClientWorkspacesStayOwnerScoped() throws Exception {
        UUID ownClient = jdbc.queryForObject("SELECT client_id FROM app_user WHERE login='demo-client'", UUID.class);
        AccountSummary own = open(ownClient);
        AccountSummary foreign = open(newClient());
        Map<String, List<String>> navigation = Map.of(
            "admin", List.of("/", "/admin/users", "/clients", "/accounts", "/workspace/statements", "/reports", "/audit"),
            "operator", List.of("/", "/clients", "/accounts", "/workspace/operations", "/workspace/statements"),
            "accountant", List.of("/", "/clients", "/accounts", "/workspace/operations", "/workspace/statements", "/reports"),
            "manager", List.of("/", "/clients", "/accounts", "/workspace/operations", "/workspace/statements", "/reports", "/audit"),
            "client", List.of("/", "/me/client", "/my/accounts", "/workspace/operations", "/workspace/statements"));
        List<String> allSections = List.of("/admin/users", "/clients", "/accounts", "/me/client", "/my/accounts",
            "/workspace/operations", "/workspace/statements", "/reports", "/audit");
        for (String role : navigation.keySet()) {
            String login = "demo-" + role;
            String html = page("/", login).getResponse().getContentAsString();
            String sidebar = html.substring(html.indexOf("<aside"), html.indexOf("</aside>"));
            for (String section : allSections) {
                String href = "href=\"" + section + "\"";
                if (navigation.get(role).contains(section)) assertThat(sidebar).contains(href);
                else assertThat(sidebar).doesNotContain(href);
            }
            page("/workspace/statements", login);
            mvc.perform(get("/workspace/operations").secure(true).with(authentication(auth(login))))
                .andExpect("admin".equals(role) ? status().isForbidden() : status().isOk());
            mvc.perform(get("/ui/clients/search").param("q", "Dashboard").secure(true).with(authentication(auth(login))))
                .andExpect("client".equals(role) ? status().isForbidden() : status().isOk());
        }
        for (String workspace : List.of("operations", "statements")) {
            MvcResult result = page("/workspace/" + workspace, "demo-client");
            assertThat(accountPage(result, "accounts")).extracting(AccountSummary::id).contains(own.id()).doesNotContain(foreign.id());
            assertThat(result.getResponse().getContentAsString()).doesNotContain(foreign.accountNumber());
        }
    }

    @Test
    void userClientPickerCanExcludeArchivedClientsAndClearOnlyThePendingSelection() throws Exception {
        UUID active = newClient();
        UUID archived = newClient();
        as("demo-operator");
        AccountPrincipal operator = principal("demo-operator");
        clients.changeStatus(archived, clients.get(archived).version(), "ARCHIVED", "Проверка выбора клиента",
            operator.id(), operator.username(), UUID.randomUUID());
        mvc.perform(get("/ui/clients/search").param("q", "Dashboard").secure(true)
                .with(authentication(auth("demo-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(archived.toString())));
        mvc.perform(get("/ui/clients/search").param("q", "Dashboard").param("activeOnly", "true").secure(true)
                .with(authentication(auth("demo-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(active.toString())))
            .andExpect(jsonPath("$[*].id", not(hasItem(archived.toString()))));

        as("demo-admin");
        AccountPrincipal admin = principal("demo-admin");
        var target = users.create("linked-" + UUID.randomUUID(), "Клиент выбора", "dashboard-client-password",
            "CLIENT", active, admin.id(), admin.username(), UUID.randomUUID());
        MvcResult normal = page("/admin/users/" + target.id() + "/edit", "demo-admin");
        assertThat(((AccessChangeForm) normal.getModelAndView().getModel().get("accessForm")).getClientId()).isEqualTo(active);
        assertThat(normal.getResponse().getContentAsString()).contains("data-active-only=\"true\"", "clearClient=true");
        MvcResult cleared = mvc.perform(get("/admin/users/" + target.id() + "/edit").param("clearClient", "true")
                .secure(true).with(authentication(auth("demo-admin"))))
            .andExpect(status().isOk()).andReturn();
        assertThat(((AccessChangeForm) cleared.getModelAndView().getModel().get("accessForm")).getClientId()).isNull();
        assertThat(jdbc.queryForObject("SELECT client_id FROM app_user WHERE id=?", UUID.class, target.id())).isEqualTo(active);
        assertThat(jdbc.queryForObject("SELECT role_code FROM app_user WHERE id=?", String.class, target.id())).isEqualTo("CLIENT");
    }

    private MvcResult page(String path, String login) throws Exception {
        return mvc.perform(get(path).secure(true).with(authentication(auth(login))))
            .andExpect(status().isOk()).andReturn();
    }

    @SuppressWarnings("unchecked")
    private Page<AccountSummary> accountPage(MvcResult result, String attribute) {
        return (Page<AccountSummary>) result.getModelAndView().getModel().get(attribute);
    }

    private AccountPrincipal principal(String login) { return (AccountPrincipal) principals.loadUserByUsername(login); }

    private UsernamePasswordAuthenticationToken auth(String login) {
        AccountPrincipal actor = principal(login);
        return new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities());
    }

    private void as(String login) { SecurityContextHolder.getContext().setAuthentication(auth(login)); }

    private static LocalDate today() { return LocalDate.now(ZoneId.of("Europe/Moscow")); }

    private UUID newClient() {
        as("demo-operator");
        AccountPrincipal actor = principal("demo-operator");
        ClientForm form = new ClientForm();
        form.setClientType("PERSON"); form.setDisplayName("Dashboard " + UUID.randomUUID());
        form.setIdentifierType("DEMO"); form.setIdentifierValue(UUID.randomUUID().toString());
        return clients.create(form, actor.id(), actor.username(), UUID.randomUUID()).id();
    }

    private AccountSummary open(UUID client) {
        as("demo-operator");
        AccountPrincipal actor = principal("demo-operator");
        return accounts.open(client, UUID.randomUUID(), today().minusDays(40), actor.id(), actor.username(), UUID.randomUUID());
    }

    private String newAccountant() {
        as("demo-admin");
        AccountPrincipal actor = principal("demo-admin");
        String login = "accountant-" + UUID.randomUUID();
        users.create(login, "Бухгалтер теста", "dashboard-accountant-password", "ACCOUNTANT", null,
            actor.id(), actor.username(), UUID.randomUUID());
        return login;
    }

    private OperationSummary draft(String login, UUID account, int index) {
        as(login);
        AccountPrincipal actor = principal(login);
        OperationForm form = new OperationForm();
        LocalDate date = today().minusDays(index);
        form.setKind("CHARGE"); form.setAmount("1.00"); form.setOperationDate(date.toString());
        form.setPeriodStart(date.withDayOfMonth(1).toString()); form.setBasis("Черновик Dashboard " + index);
        return operations.prepare(account, form, actor.id(), actor.username(), UUID.randomUUID());
    }
}
