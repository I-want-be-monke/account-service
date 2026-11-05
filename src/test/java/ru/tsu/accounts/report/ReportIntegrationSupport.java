package ru.tsu.accounts.report;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.operation.OperationForm;
import ru.tsu.accounts.operation.OperationPostingService;
import ru.tsu.accounts.operation.OperationService;
import ru.tsu.accounts.operation.OperationSummary;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;
import ru.tsu.accounts.statement.StatementService;
import ru.tsu.accounts.support.LocalPostgres;
import ru.tsu.accounts.support.PostgresContextLifecycle;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
abstract class ReportIntegrationSupport extends PostgresContextLifecycle {
    static final UUID CLIENT_ONE = fixtureId("101");
    static final UUID CLIENT_TWO = fixtureId("102");
    static final UUID ACCOUNT_A = fixtureId("201");
    static final UUID ACCOUNT_B = fixtureId("202");
    static final UUID ACCOUNT_C = fixtureId("203");
    static final LocalDate OCTOBER_START = LocalDate.of(2026, 10, 1);
    static final LocalDate OCTOBER_END = LocalDate.of(2026, 10, 31);
    static final Map<String, String> OCTOBER = Map.of("month", "2026-10");
    static final Map<String, String> OCTOBER_MOVEMENT = Map.of("from", "2026-10-01", "to", "2026-10-31");

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
        registry.add("account.demo.passwords.admin", () -> "correct-password-for-e1");
        registry.add("account.demo.passwords.operator", () -> "operator-password-for-e1");
        registry.add("account.demo.passwords.accountant", () -> "accountant-password-for-e1");
        registry.add("account.demo.passwords.manager", () -> "manager-password-for-e1");
        registry.add("account.demo.passwords.client", () -> "client-password-for-e1");
    }

    @Autowired ReportService reports;
    @Autowired StatementService statements;
    @Autowired AccountService accounts;
    @Autowired OperationService drafts;
    @Autowired OperationPostingService posting;
    @Autowired DatabaseUserDetailsService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;

    @BeforeEach
    void seedIndependentControlSet() throws IOException {
        String connectedUrl = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        assertThat(connectedUrl).as("fixture target is this class's disposable PostgreSQL container")
            .isEqualTo(postgres.getJdbcUrl());
        String fixture = new ClassPathResource("db/report-control.sql").getContentAsString(StandardCharsets.UTF_8);
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            // The historical fixtures contain immutable POSTED rows. Reset only the disposable test database.
            jdbc.execute("TRUNCATE TABLE financial_operation, account_status_history, personal_account");
            jdbc.update("DELETE FROM client WHERE id IN (?, ?)", CLIENT_ONE, CLIENT_TWO);
            jdbc.execute("SET LOCAL accounts.e9_fixture = 'isolated'");
            jdbc.execute(fixture);
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    static LocalDate today() { return LocalDate.now(ZoneId.of("Europe/Moscow")); }

    private static UUID fixtureId(String suffix) {
        return UUID.fromString("e9000000-0000-4000-8000-000000000" + suffix);
    }

    AccountPrincipal principal(String login) { return (AccountPrincipal) users.loadUserByUsername(login); }

    UsernamePasswordAuthenticationToken auth(String login) {
        var principal = principal(login);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    void as(String login) { SecurityContextHolder.getContext().setAuthentication(auth(login)); }

    Report report(String type, Map<String, String> parameters) {
        as("demo-manager");
        return reports.generate(ReportQuery.parse(type, parameters));
    }

    String accountNumber(UUID id) {
        return jdbc.queryForObject("SELECT account_number FROM personal_account WHERE id = ?", String.class, id);
    }

    long operationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM financial_operation", Long.class);
    }

    OperationSummary prepareCharge(UUID account, LocalDate day, String amount) {
        as("demo-accountant");
        var actor = principal("demo-accountant");
        var form = new OperationForm();
        form.setKind("CHARGE");
        form.setAmount(amount);
        form.setOperationDate(day.toString());
        form.setPeriodStart(day.withDayOfMonth(1).toString());
        form.setBasis("Интеграционная проверка отчётности E9");
        return drafts.prepare(account, form, actor.id(), actor.username(), UUID.randomUUID());
    }

    OperationSummary postDraft(OperationSummary draft) {
        as("demo-accountant");
        var actor = principal("demo-accountant");
        return posting.post(draft.id(), draft.version(), UUID.randomUUID(), drafts.readyFingerprint(draft.id()),
            actor.id(), actor.username(), UUID.randomUUID());
    }

    void changeStatus(UUID account, String status) {
        as("demo-operator");
        var actor = principal("demo-operator");
        var summary = accounts.get(account);
        accounts.changeStatus(account, summary.version(), status, "Проверка фильтра E9",
            actor.id(), actor.username(), UUID.randomUUID());
    }
}
