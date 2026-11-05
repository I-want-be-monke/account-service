package ru.tsu.accounts.acceptance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.account.AccountSummary;
import ru.tsu.accounts.client.ClientForm;
import ru.tsu.accounts.client.ClientService;
import ru.tsu.accounts.client.ClientSummary;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.identity.UserManagementService;
import ru.tsu.accounts.identity.UserSummary;
import ru.tsu.accounts.operation.CorrectionForm;
import ru.tsu.accounts.operation.CorrectionService;
import ru.tsu.accounts.operation.OperationForm;
import ru.tsu.accounts.operation.OperationPostingService;
import ru.tsu.accounts.operation.OperationService;
import ru.tsu.accounts.operation.OperationSummary;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;
import ru.tsu.accounts.support.LocalPostgres;
import ru.tsu.accounts.support.PostgresContextLifecycle;

import static ru.tsu.accounts.acceptance.AcceptanceScenariosIT.Role.*;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class AcceptanceScenariosIT extends PostgresContextLifecycle {
    enum Role {
        ADMIN("correct-password-for-e1"), OPERATOR("operator-password-for-e1"), ACCOUNTANT("accountant-password-for-e1"),
        MANAGER("manager-password-for-e1"), CLIENT("client-password-for-e1");

        private final String password;
        Role(String password) { this.password = password; }
        String login() { return "demo-" + name().toLowerCase(Locale.ROOT); }
    }

    private static final String RUNTIME = "import_test_app";
    private static final String NEW_PASSWORD = "acceptance-only-user-password";
    private static final String REASON = "Синтетическая приёмка E11";
    private static final Set<Role> ALL = EnumSet.allOf(Role.class);
    private static final Set<Role> STAFF = EnumSet.of(ADMIN, OPERATOR, ACCOUNTANT, MANAGER);
    private static final Set<Role> HISTORY = EnumSet.of(OPERATOR, ACCOUNTANT, MANAGER, CLIENT);
    private static final Set<Role> REPORTS = EnumSet.of(ADMIN, ACCOUNTANT, MANAGER);
    private static final Set<Role> AUDIT = EnumSet.of(ADMIN, MANAGER);
    private static final Set<Role> ADMINS = Set.of(ADMIN);
    private static final Set<Role> OPERATORS = Set.of(OPERATOR);
    private static final Set<Role> ACCOUNTANTS = Set.of(ACCOUNTANT);
    private static final Set<Role> CLIENTS = Set.of(CLIENT);
    private static final Pattern TOKEN = Pattern.compile("name=\"token\" value=\"([^\"]+)\"");
    private static final String BUSINESS_DIGEST = List.of("client", "app_user", "personal_account", "account_status_history",
        "financial_operation", "import_run").stream().map(table -> "(SELECT coalesce(string_agg(to_jsonb(t)::text, '' ORDER BY t.id), '') FROM "
            + table + " t)").collect(Collectors.joining(",", "SELECT md5(concat(", "))"));

    @Container
    static final PostgreSQLContainer<?> postgres = LocalPostgres.create()
        .withDatabaseName("accounts").withUsername("account_owner")
        .withPassword("test-owner-password").withInitScript("db/import-test-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME);
        registry.add("spring.datasource.password", () -> "test-import-runtime-password");
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        for (Role role : Role.values()) {
            registry.add("account.demo.passwords." + role.name().toLowerCase(Locale.ROOT), () -> role.password);
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
    @Autowired CorrectionService corrections;

    @BeforeEach
    void verifyDisposableDatabaseAndRuntimeRole() {
        assertThat(jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL()))
            .isEqualTo(postgres.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME);
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest(name = "SEC-04 {0}")
    @EnumSource(Role.class)
    void serverEnforcesRoleMatrixForBusinessGetAndPostWithRealJdbcSessions(Role role) throws Exception {
        var sample = matrixFixture();
        var browser = login(role);
        var client = "/clients/" + sample.client().id();
        var archive = "/clients/" + sample.archive().id();
        var own = "/accounts/" + sample.own().id();
        var close = "/accounts/" + sample.close().id();
        var posted = "/operations/" + sample.charge().id();
        var draft = "/operations/" + sample.draft().id();
        var correction = "/operations/" + sample.correction().id();
        var user = "/admin/users/" + sample.user().id();
        var date = today().toString();
        var period = "?from=" + date + "&to=" + date;
        var report = "?type=DEBT&asOf=" + date;

        browser.read("/", ALL);
        browser.read("/clients", STAFF);
        browser.read(client, STAFF);
        browser.read("/clients/new", OPERATORS);
        browser.read(client + "/edit", OPERATORS);
        browser.read(archive + "/status", OPERATORS);
        browser.read(client + "/accounts/new", OPERATORS);
        browser.read("/accounts", STAFF);
        browser.read(own, ALL);
        browser.read(close + "/status", OPERATORS);
        browser.read(own + "/operations", HISTORY);
        browser.read(own + "/operations/new?kind=CHARGE", ACCOUNTANTS);
        browser.read(own + "/operations/new?kind=PAYMENT", ACCOUNTANTS);
        browser.read(posted, HISTORY);
        browser.read(draft, ACCOUNTANTS);
        browser.read(draft + "/edit", ACCOUNTANTS);
        browser.read(posted + "/corrections/new", ACCOUNTANTS);
        browser.read(correction + "/corrections/edit", ACCOUNTANTS);
        browser.read("/me/client", CLIENTS);
        browser.read("/my/accounts", CLIENTS);
        browser.read(own + "/statement", ALL);
        browser.read(own + "/statement/result" + period, ALL);
        browser.read(own + "/statement/print" + period, ALL);
        browser.read(own + "/statement.csv" + period, ALL);
        browser.read("/api/v1" + own, ALL);
        browser.read("/api/v1" + own + "/operations", HISTORY);
        browser.read("/api/v1" + own + "/statement" + period, ALL);
        browser.read("/reports", REPORTS);
        browser.read("/reports/result" + report, REPORTS);
        browser.read("/reports/print" + report, REPORTS);
        browser.read("/reports.csv" + report, REPORTS);
        browser.read("/api/v1/reports/DEBT?asOf=" + date, REPORTS);
        browser.read("/audit", AUDIT);
        browser.read("/audit/" + sample.businessAuditId(), AUDIT);
        browser.read("/audit/" + sample.securityAuditId(), ADMINS);
        browser.read("/audit?category=SECURITY", ADMINS);
        browser.read("/admin/users", ADMINS);
        browser.read("/admin/users/new", ADMINS);
        browser.read(user + "/edit", ADMINS);

        browser.write("/clients/new", clientForm("Проверка формы E11", "E11-FORM-" + shortId()), OPERATORS);
        browser.write(client + "/edit", Map.of("clientType", "PERSON", "displayName", "Обновлённая карточка E11",
            "identifierType", "DEMO", "identifierValue", sample.client().identifierValue(),
            "version", Long.toString(sample.client().version())), OPERATORS);
        browser.write(archive + "/status", transition(sample.archive().version(), "ARCHIVED"), OPERATORS);
        browser.confirm(archive + "/confirm", OPERATORS);
        browser.write(client + "/accounts/new", Map.of("openedOn", date, "openingId", UUID.randomUUID().toString()), OPERATORS);
        browser.write(close + "/status", transition(sample.close().version(), "CLOSED"), OPERATORS);
        browser.confirm(close + "/confirm", OPERATORS);

        browser.write(own + "/operations/new", chargeForm("80.00", today()), ACCOUNTANTS);
        browser.write(own + "/operations/new", paymentForm("20.00", "E11-MATRIX-" + shortId()), ACCOUNTANTS);
        browser.write(draft + "/edit", Map.of("kind", "CHARGE", "amount", "51.00", "operationDate", date,
            "periodStart", today().withDayOfMonth(1).toString(), "basis", REASON,
            "version", Long.toString(sample.draft().version())), ACCOUNTANTS);
        browser.write(posted + "/corrections/new", correctionForm("800.00"), ACCOUNTANTS);
        browser.write(correction + "/corrections/edit", Map.of("targetAmount", "850.00", "operationDate", date,
            "reason", REASON, "basis", REASON, "version", Long.toString(sample.correction().version())), ACCOUNTANTS);
        browser.confirm(draft + "/discard", ACCOUNTANTS);
        browser.confirm(correction + "/confirm", ACCOUNTANTS);

        browser.write("/admin/users/new", Map.of("login", "e11-form-" + shortId(), "fullName", "Новый оператор E11",
            "password", NEW_PASSWORD, "roleCode", "OPERATOR"), ADMINS);
        browser.write(user + "/edit", Map.of("login", sample.user().login(), "fullName", "Обновлённый пользователь E11",
            "version", Long.toString(sample.user().version())), ADMINS);
        var previous = role == ADMIN ? login(sample.user().login(), NEW_PASSWORD, OPERATOR) : null;
        if (previous != null) previous.read("/clients", STAFF);
        long userVersion = jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?", Long.class, sample.user().id());
        browser.write(user + "/prepare", Map.of("version", Long.toString(userVersion), "roleCode", "MANAGER",
            "status", "BLOCKED", "reason", REASON), ADMINS);
        browser.confirm(user + "/confirm", ADMINS);
        if (previous != null) {
            previous.call(get("/clients"), 302);
            previous.call(get("/api/v1/accounts/" + sample.own().id()), 401);
            assertThat(jdbc.queryForMap("SELECT role_code, status FROM app_user WHERE id = ?", sample.user().id()))
                .containsEntry("role_code", "MANAGER").containsEntry("status", "BLOCKED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
                Long.class, sample.user().login())).isZero();
        }
        browser.write("/logout", Map.of(), ALL);
        browser.call(get("/"), 302);
        assertThat(browser.reads).isEqualTo(44);
        assertThat(browser.writes).isEqualTo(19);
        System.out.printf("SEC-04 role=%s GET=%d POST=%d allowed=%d denied=%d csrf_denied=%d%n",
            role, browser.reads, browser.writes, browser.allowed, browser.denied, browser.csrfDenied);
    }

    @Test
    void operatorToAccountantHttpFlowCoversAt01To04AndAt08() throws Exception {
        var operator = login(OPERATOR);
        String identifier = "E11-AT01-" + shortId();
        var created = operator.write("/clients/new", clientForm("Контрольный клиент AT-01", identifier), OPERATORS);
        UUID clientId = redirectedId(created, "/clients/");
        var accountId = UUID.randomUUID();
        var openDate = today().minusDays(1);
        var opening = Map.of("openingId", accountId.toString(), "openedOn", openDate.toString());
        var firstOpening = operator.write("/clients/" + clientId + "/accounts/new", opening, OPERATORS);
        var repeatedOpening = operator.write("/clients/" + clientId + "/accounts/new", opening, OPERATORS);
        assertThat(redirectedId(firstOpening, "/accounts/")).isEqualTo(accountId);
        assertThat(redirectedId(repeatedOpening, "/accounts/")).isEqualTo(accountId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM client WHERE identifier_value = ?", Long.class, identifier)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT client_id, status, current_balance FROM personal_account WHERE id = ?", accountId))
            .containsEntry("client_id", clientId).containsEntry("status", "OPEN").containsEntry("current_balance", new BigDecimal("0.00"));
        assertThat(successAudit("CLIENT_CREATED", clientId)).isEqualTo(1);
        assertThat(successAudit("ACCOUNT_OPENED", accountId)).isEqualTo(1);

        var accountant = login(ACCOUNTANT);
        String newOperation = "/accounts/" + accountId + "/operations/new";
        UUID charge = redirectedId(accountant.write(newOperation, chargeForm("1000.00", openDate), ACCOUNTANTS), "/operations/");
        assertThat(balance(accountId)).isEqualByComparingTo("0.00");
        String chargeConfirmation = "/operations/" + charge + "/confirm";
        String chargeToken = token(accountant.read(chargeConfirmation, ACCOUNTANTS));
        var separateSession = login(ACCOUNTANT);
        String before = digest();
        separateSession.call(post(chargeConfirmation).with(csrf()).param("token", chargeToken), 409);
        accountant.call(post(chargeConfirmation).with(csrf()).param("token", "fabricated"), 409);
        assertThat(digest()).isEqualTo(before);
        accountant.write(chargeConfirmation, Map.of("token", chargeToken), ACCOUNTANTS);
        assertThat(balance(accountId)).isEqualByComparingTo("1000.00");
        assertThat(successAudit("OPERATION_POSTED", charge)).isEqualTo(1);
        assertThat(postedCount(accountId)).isEqualTo(1);

        String document = "E11-AT03-" + shortId();
        var paymentForm = paymentForm("400.00", document);
        UUID payment = redirectedId(accountant.write(newOperation, paymentForm, ACCOUNTANTS), "/operations/");
        accountant.confirm("/operations/" + payment + "/confirm", ACCOUNTANTS);
        before = digest();
        var duplicate = accountant.call(request(newOperation, paymentForm).with(csrf()), 409);
        assertThat(html(duplicate)).contains("Документ");
        assertThat(digest()).isEqualTo(before);
        assertThat(balance(accountId)).isEqualByComparingTo("600.00");
        assertThat(postedCount(accountId)).isEqualTo(2);
        assertThat(successAudit("OPERATION_POSTED", payment)).isEqualTo(1);

        var detail = accountant.read("/operations/" + payment, HISTORY);
        assertThat(html(detail)).contains("/operations/" + payment + "/corrections/new")
            .doesNotContain("/operations/" + payment + "/discard");
        accountant.call(get("/operations/" + payment + "/discard"), 403);
        accountant.call(delete("/operations/" + payment).with(csrf()), 405);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM financial_operation WHERE id = ?", payment))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(digest()).isEqualTo(before);

        UUID chargeFix = redirectedId(accountant.write("/operations/" + charge + "/corrections/new",
            correctionForm("900.00"), ACCOUNTANTS), "/operations/");
        UUID paymentFix = redirectedId(accountant.write("/operations/" + payment + "/corrections/new",
            correctionForm("350.00"), ACCOUNTANTS), "/operations/");
        accountant.confirm("/operations/" + chargeFix + "/confirm", ACCOUNTANTS);
        accountant.confirm("/operations/" + paymentFix + "/confirm", ACCOUNTANTS);
        String date = today().toString();
        accountant.call(get("/api/v1/accounts/" + accountId + "/statement?from=" + date + "&to=" + date), 200, result -> {
            jsonPath("$.openingBalance").value("1000.00").match(result);
            jsonPath("$.increase").value("50.00").match(result);
            jsonPath("$.decrease").value("500.00").match(result);
            jsonPath("$.closingBalance").value("550.00").match(result);
            jsonPath("$.operations.length()").value(3).match(result);
            jsonPath("$.operations[0].id").value(payment.toString()).match(result);
            jsonPath("$.operations[0].delta").value("-400.00").match(result);
            jsonPath("$.operations[1].id").value(chargeFix.toString()).match(result);
            jsonPath("$.operations[1].delta").value("-100.00").match(result);
            jsonPath("$.operations[1].originalOperationId").value(charge.toString()).match(result);
            jsonPath("$.operations[2].id").value(paymentFix.toString()).match(result);
            jsonPath("$.operations[2].delta").value("50.00").match(result);
            jsonPath("$.operations[2].originalOperationId").value(payment.toString()).match(result);
        });
        assertThat(postedCount(accountId)).isEqualTo(4);
        assertThat(balance(accountId)).isEqualByComparingTo("550.00");
        assertThat(rebuiltBalance(accountId)).isEqualByComparingTo("550.00");
        assertThat(successAudit("OPERATION_CORRECTION_POSTED", chargeFix)).isEqualTo(1);
        assertThat(successAudit("OPERATION_CORRECTION_POSTED", paymentFix)).isEqualTo(1);
    }

    @Test
    void clientCannotReadForeignUuidAcrossCardHistoryOperationAndEveryStatementFormat() throws Exception {
        var own = newAccount(ownClient(), today());
        String privateName = "E11-FOREIGN-PRIVATE-NAME-" + shortId();
        var foreignClient = newClient(privateName);
        var foreign = newAccount(foreignClient.id(), today());
        String privateDocument = "E11-FOREIGN-DOC-" + shortId();
        var foreignOperation = postCommand(command(ACCOUNTANT.login(), foreign.id(), "CHARGE", "723.49", privateDocument));
        var client = login(CLIENT);
        String ownPath = "/accounts/" + own.id();
        String foreignPath = "/accounts/" + foreign.id();
        String date = today().toString();
        String period = "?from=" + date + "&to=" + date;
        client.read(ownPath, ALL);
        client.read("/api/v1" + ownPath + "/statement" + period, ALL);
        for (String route : List.of("/clients/" + foreignClient.id(), foreignPath, foreignPath + "/operations",
            "/operations/" + foreignOperation.id(), foreignPath + "/statement", foreignPath + "/statement/result" + period,
            foreignPath + "/statement/print" + period, foreignPath + "/statement.csv" + period, "/api/v1" + foreignPath,
            "/api/v1" + foreignPath + "/operations", "/api/v1" + foreignPath + "/statement" + period)) {
            var response = client.call(get(route), 403).getResponse();
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(privateName, privateDocument,
                foreign.accountNumber(), "723.49");
            assertThat(response.getHeader("Content-Disposition")).isNull();
            assertThat(response.getHeader("Location")).isNull();
        }
    }

    @Test
    void auditFailureAfterDatabasePostingRollsBackAt07AndRuntimeCannotAlterAudit() throws Exception {
        var account = newAccount(ownClient(), today());
        var pending = command(ACCOUNTANT.login(), account.id(), "CHARGE", "80.00", null).draft();
        var accountant = login(ACCOUNTANT);
        String endpoint = "/operations/" + pending.id() + "/confirm";
        String token = token(accountant.read(endpoint, ACCOUNTANTS));
        String before = digest();
        var owner = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        owner.execute("CREATE SEQUENCE e11_posting_fault_seen");
        owner.execute("GRANT USAGE ON SEQUENCE e11_posting_fault_seen TO account_runtime");
        owner.execute("""
            CREATE FUNCTION e11_fail_posting_audit() RETURNS trigger LANGUAGE plpgsql AS $fault$
            BEGIN
                IF EXISTS (SELECT 1 FROM financial_operation o JOIN personal_account a ON a.id = o.account_id
                           WHERE o.id = NEW.object_id::uuid AND o.storage_status = 'POSTED' AND a.current_balance = 80.00) THEN
                    PERFORM nextval('e11_posting_fault_seen');
                END IF;
                RAISE EXCEPTION 'intentional E11 mandatory audit failure';
            END;
            $fault$
            """);
        owner.execute("""
            CREATE TRIGGER e11_fail_posting_audit BEFORE INSERT ON audit_event FOR EACH ROW
            WHEN (NEW.action = 'OPERATION_POSTED' AND NEW.result = 'SUCCESS') EXECUTE FUNCTION e11_fail_posting_audit()
            """);
        try {
            var failed = accountant.call(post(endpoint).with(csrf()).param("token", token), 503);
            assertThat(html(failed)).doesNotContain("intentional E11", "SQLException", "80.00");
            assertThat(owner.queryForObject("SELECT is_called FROM e11_posting_fault_seen", Boolean.class)).isTrue();
            assertThat(digest()).isEqualTo(before);
            assertThat(balance(account.id())).isEqualByComparingTo("0.00");
            assertThat(postedCount(account.id())).isZero();
            assertThat(successAudit("OPERATION_POSTED", pending.id())).isZero();
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event
                WHERE object_id = ? AND category = 'BUSINESS' AND action = 'OPERATION_POST_DENIED'
                  AND result = 'DENIED' AND reason_code = 'POSTING_DATABASE_FAILURE' AND request_id = ?::uuid
                """, Long.class, pending.id().toString(), failed.getResponse().getHeader("X-Request-Id")))
                .as("failed posting is audited after the ledger transaction rolls back")
                .isEqualTo(1);
        } finally {
            owner.execute("DROP TRIGGER e11_fail_posting_audit ON audit_event");
            owner.execute("DROP FUNCTION e11_fail_posting_audit()");
            owner.execute("DROP SEQUENCE e11_posting_fault_seen");
        }
        accountant.write(endpoint, Map.of("token", token), ACCOUNTANTS);
        assertThat(balance(account.id())).isEqualByComparingTo("80.00");
        long auditId = auditId("OPERATION_POSTED", pending.id());
        assertAuditPermissionDenied(() -> jdbc.update("UPDATE audit_event SET reason_code = 'tampered' WHERE id = ?", auditId));
        assertAuditPermissionDenied(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", auditId));
        assertThat(successAudit("OPERATION_POSTED", pending.id())).isEqualTo(1);
    }

    @Test
    void concurrentDifferentAccountantsReconcileLedgerAndRejectDuplicateAndStaleCorrection() throws Exception {
        var other = newUser("ACCOUNTANT");
        var account = newAccount(ownClient(), today());
        var charge = command(ACCOUNTANT.login(), account.id(), "CHARGE", "1000.00", null);
        var payment = command(other.login(), account.id(), "PAYMENT", "400.00", "E11-CONTENTION-" + shortId());
        assertThat(race(() -> outcome(charge), () -> outcome(payment))).containsExactly("POSTED", "POSTED");
        assertThat(balance(account.id())).isEqualByComparingTo("600.00");
        assertThat(postCommand(payment).id()).isEqualTo(payment.draft().id());
        assertThat(postedCount(account.id())).isEqualTo(2);

        String duplicate = "E11-DUPLICATE-" + shortId();
        var first = command(ACCOUNTANT.login(), account.id(), "PAYMENT", "50.00", duplicate);
        var second = command(other.login(), account.id(), "PAYMENT", "50.00", duplicate);
        assertThat(race(() -> outcome(first), () -> outcome(second))).containsExactlyInAnyOrder("POSTED", "CONFLICT");
        assertThat(balance(account.id())).isEqualByComparingTo("550.00");

        var firstFix = correctionCommand(ACCOUNTANT.login(), charge.draft().id(), "900.00");
        var secondFix = correctionCommand(other.login(), charge.draft().id(), "800.00");
        assertThat(race(() -> outcome(firstFix), () -> outcome(secondFix))).containsExactlyInAnyOrder("POSTED", "CONFLICT");
        assertThat(balance(account.id())).isIn(new BigDecimal("450.00"), new BigDecimal("350.00"));
        assertThat(balance(account.id())).isEqualByComparingTo(rebuiltBalance(account.id()));
        assertThat(postedCount(account.id())).isEqualTo(4);
        assertThat(successAudit("OPERATION_CORRECTION_POSTED", firstFix.draft().id())
            + successAudit("OPERATION_CORRECTION_POSTED", secondFix.draft().id())).isEqualTo(1);

        var validPaymentA = command(ACCOUNTANT.login(), account.id(), "PAYMENT", "5.00", "E11-DISTINCT-" + shortId());
        var validPaymentB = command(other.login(), account.id(), "PAYMENT", "7.00", "E11-DISTINCT-" + shortId());
        BigDecimal beforePayments = balance(account.id());
        assertThat(race(() -> outcome(validPaymentA), () -> outcome(validPaymentB))).containsExactly("POSTED", "POSTED");
        assertThat(balance(account.id())).isEqualByComparingTo(beforePayments.subtract(new BigDecimal("12.00")));

        var replay = command(ACCOUNTANT.login(), account.id(), "PAYMENT", "3.00", "E11-KEY-" + shortId());
        assertThat(race(() -> outcome(replay), () -> outcome(replay))).containsExactly("POSTED", "POSTED");
        assertThat(balance(account.id())).isEqualByComparingTo(beforePayments.subtract(new BigDecimal("15.00")));
        assertThat(balance(account.id())).isEqualByComparingTo(rebuiltBalance(account.id()));
        assertThat(postedCount(account.id())).isEqualTo(7);
        assertThat(successAudit("OPERATION_POSTED", replay.draft().id())).isEqualTo(1);

        var statusAccount = newAccount(ownClient(), today());
        var competingCharge = command(other.login(), statusAccount.id(), "CHARGE", "19.00", null);
        var serialized = race(() -> outcome(competingCharge), () -> suspendOutcome(statusAccount));
        if (serialized.getFirst().equals("POSTED")) {
            assertThat(serialized).containsExactly("POSTED", "CONFLICT");
            assertThat(balance(statusAccount.id())).isEqualByComparingTo("19.00");
            assertThat(postedCount(statusAccount.id())).isEqualTo(1);
            assertThat(successAudit("ACCOUNT_STATUS_CHANGED", statusAccount.id())).isZero();
            assertThat(accountStatus(statusAccount.id())).isEqualTo("OPEN");
        } else {
            assertThat(serialized).containsExactly("CONFLICT", "SUSPENDED");
            assertThat(balance(statusAccount.id())).isEqualByComparingTo("0.00");
            assertThat(postedCount(statusAccount.id())).isZero();
            assertThat(successAudit("ACCOUNT_STATUS_CHANGED", statusAccount.id())).isEqualTo(1);
            assertThat(accountStatus(statusAccount.id())).isEqualTo("SUSPENDED");
        }
        assertThat(balance(statusAccount.id())).isEqualByComparingTo(rebuiltBalance(statusAccount.id()));
    }

    private String suspendOutcome(AccountSummary account) {
        var operator = as(OPERATOR.login());
        try {
            return accounts.changeStatus(account.id(), account.version(), "SUSPENDED", REASON,
                operator.id(), operator.username(), UUID.randomUUID()).status();
        } catch (UserCommandException rejected) {
            return rejected.status().name();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String accountStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM personal_account WHERE id = ?", String.class, id);
    }

    private MatrixFixture matrixFixture() {
        var client = newClient("Карточка для матрицы E11");
        var archive = newClient("Архив для матрицы E11");
        var own = newAccount(ownClient(), today());
        var close = newAccount(ownClient(), today());
        var charge = postCommand(command(ACCOUNTANT.login(), own.id(), "CHARGE", "1000.00", null));
        var draft = command(ACCOUNTANT.login(), own.id(), "CHARGE", "50.00", null).draft();
        var correction = correctionCommand(ACCOUNTANT.login(), charge.id(), "900.00").draft();
        var user = newUser("OPERATOR");
        return new MatrixFixture(client, archive, own, close, charge, draft, correction, user,
            auditId("OPERATION_POSTED", charge.id()), auditId("USER_CREATED", user.id()));
    }

    private ClientSummary newClient(String name) {
        var operator = as(OPERATOR.login());
        var form = new ClientForm();
        form.setClientType("PERSON");
        form.setDisplayName(name);
        form.setIdentifierType("DEMO");
        form.setIdentifierValue("E11-FIXTURE-" + shortId());
        return clients.create(form, operator.id(), operator.username(), UUID.randomUUID());
    }

    private AccountSummary newAccount(UUID client, LocalDate date) {
        var operator = as(OPERATOR.login());
        return accounts.open(client, UUID.randomUUID(), date, operator.id(), operator.username(), UUID.randomUUID());
    }

    private UserSummary newUser(String role) {
        var admin = as(ADMIN.login());
        return users.create("e11-user-" + shortId(), "Пользователь приёмки E11", NEW_PASSWORD, role, null,
            admin.id(), admin.username(), UUID.randomUUID());
    }

    private UUID ownClient() {
        return jdbc.queryForObject("SELECT client_id FROM app_user WHERE login = 'demo-client'", UUID.class);
    }

    private PostCommand command(String login, UUID account, String kind, String amount, String document) {
        var actor = as(login);
        var form = new OperationForm();
        form.setKind(kind);
        form.setAmount(amount);
        form.setOperationDate(today().toString());
        form.setBasis(REASON);
        form.setExternalNumber(document);
        if ("CHARGE".equals(kind)) form.setPeriodStart(today().withDayOfMonth(1).toString());
        else form.setPaymentMethod("MANUAL_TRANSFER");
        var draft = operations.prepare(account, form, actor.id(), actor.username(), UUID.randomUUID());
        return new PostCommand(login, draft, UUID.randomUUID(), operations.readyFingerprint(draft.id()));
    }

    private PostCommand correctionCommand(String login, UUID source, String target) {
        var actor = as(login);
        var form = new CorrectionForm();
        form.setOperationDate(today().toString());
        form.setTargetAmount(target);
        form.setReason(REASON);
        form.setBasis(REASON);
        var draft = corrections.prepare(source, form, actor.id(), actor.username(), UUID.randomUUID());
        return new PostCommand(login, draft, UUID.randomUUID(), operations.readyFingerprint(draft.id()));
    }

    private OperationSummary postCommand(PostCommand command) {
        var actor = as(command.login());
        return posting.post(command.draft().id(), command.draft().version(), command.key(), command.fingerprint(),
            actor.id(), actor.username(), UUID.randomUUID());
    }

    private String outcome(PostCommand command) {
        try { return postCommand(command).status(); }
        catch (UserCommandException rejected) { return rejected.status().name(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private static List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(job -> workers.submit(() -> {
                ready.countDown();
                assertThat(start.await(15, TimeUnit.SECONDS)).isTrue();
                return job.call();
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        }
    }

    private AccountPrincipal as(String login) {
        var actor = (AccountPrincipal) principals.loadUserByUsername(login);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        return actor;
    }

    private Browser login(Role role) throws Exception { return login(role.login(), role.password, role); }

    private Browser login(String login, String password, Role role) throws Exception {
        SecurityContextHolder.clearContext();
        var result = mvc.perform(post("/login").secure(true).with(csrf()).param("username", login).param("password", password))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/")).andReturn();
        var header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).contains("ACCOUNT_SESSION=", "Secure", "HttpOnly");
        var value = header.split("ACCOUNT_SESSION=", 2)[1].split(";", 2)[0];
        return new Browser(role, new Cookie("ACCOUNT_SESSION", value));
    }

    private final class Browser {
        private final Role role;
        private final Cookie session;
        private int reads;
        private int writes;
        private int allowed;
        private int denied;
        private int csrfDenied;

        private Browser(Role role, Cookie session) { this.role = role; this.session = session; }

        private MvcResult read(String uri, Set<Role> permitted) throws Exception {
            reads++;
            return matrix(get(uri), permitted, 200);
        }

        private MvcResult write(String uri, Map<String, String> parameters, Set<Role> permitted) throws Exception {
            writes++;
            if (permitted.contains(role)) {
                call(request(uri, parameters), 403);
                csrfDenied++;
            }
            return matrix(request(uri, parameters).with(csrf()), permitted, 302);
        }

        private void confirm(String uri, Set<Role> permitted) throws Exception {
            var page = read(uri, permitted);
            write(uri, Map.of("token", permitted.contains(role) ? token(page) : "well-formed-unused-token"), permitted);
        }

        private MvcResult matrix(MockHttpServletRequestBuilder request, Set<Role> permitted, int allowedStatus) throws Exception {
            if (permitted.contains(role)) {
                allowed++;
                return call(request, allowedStatus);
            }
            denied++;
            return call(request, 403);
        }

        private MvcResult call(MockHttpServletRequestBuilder request, int expected,
                               org.springframework.test.web.servlet.ResultMatcher... extra) throws Exception {
            String before = expected >= 400 ? digest() : null;
            var result = mvc.perform(request.secure(true).cookie(session)).andReturn();
            assertThat(result.getResponse().getStatus()).as("%s %s %s", role, result.getRequest().getMethod(),
                result.getRequest().getRequestURI()).isEqualTo(expected);
            if (before != null) assertThat(digest()).isEqualTo(before);
            if (expected == 403) assertThat(result.getResponse().getHeader("Content-Disposition")).isNull();
            for (var check : extra) check.match(result);
            return result;
        }
    }

    private static MockHttpServletRequestBuilder request(String uri, Map<String, String> parameters) {
        var builder = post(uri);
        parameters.forEach(builder::param);
        return builder;
    }

    private static String token(MvcResult page) throws Exception {
        var matcher = TOKEN.matcher(html(page));
        assertThat(matcher.find()).as("confirmation token rendered in real form").isTrue();
        return matcher.group(1);
    }

    private static UUID redirectedId(MvcResult result, String prefix) {
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(prefix);
        return UUID.fromString(location.substring(prefix.length()).split("\\?", 2)[0]);
    }

    private static String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> clientForm(String name, String identifier) {
        return Map.of("clientType", "PERSON", "displayName", name, "identifierType", "DEMO", "identifierValue", identifier);
    }

    private static Map<String, String> transition(long version, String status) {
        return Map.of("version", Long.toString(version), "status", status, "reason", REASON);
    }

    private static Map<String, String> chargeForm(String amount, LocalDate date) {
        return Map.of("kind", "CHARGE", "amount", amount, "operationDate", date.toString(),
            "periodStart", date.withDayOfMonth(1).toString(), "basis", REASON);
    }

    private static Map<String, String> paymentForm(String amount, String document) {
        return Map.of("kind", "PAYMENT", "amount", amount, "operationDate", today().toString(),
            "paymentMethod", "MANUAL_TRANSFER", "externalNumber", document, "basis", REASON);
    }

    private static Map<String, String> correctionForm(String amount) {
        return Map.of("targetAmount", amount, "operationDate", today().toString(), "reason", REASON, "basis", REASON);
    }

    private long successAudit(String action, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND object_id = ? AND result = 'SUCCESS'",
            Long.class, action, id.toString());
    }

    private long auditId(String action, UUID id) {
        return jdbc.queryForObject("SELECT id FROM audit_event WHERE action = ? AND object_id = ? AND result = 'SUCCESS' ORDER BY id DESC LIMIT 1",
            Long.class, action, id.toString());
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("SELECT current_balance FROM personal_account WHERE id = ?", BigDecimal.class, account);
    }

    private BigDecimal rebuiltBalance(UUID account) {
        return jdbc.queryForObject("""
            SELECT a.opening_balance + coalesce(sum(o.delta), 0)
            FROM personal_account a LEFT JOIN financial_operation o ON o.account_id = a.id AND o.storage_status = 'POSTED'
            WHERE a.id = ? GROUP BY a.id
            """, BigDecimal.class, account);
    }

    private long postedCount(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM financial_operation WHERE account_id = ? AND storage_status = 'POSTED'",
            Long.class, account);
    }

    private String digest() { return jdbc.queryForObject(BUSINESS_DIGEST, String.class); }

    private static void assertAuditPermissionDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable command) {
        assertThatThrownBy(command).isInstanceOfSatisfying(DataAccessException.class, failure -> {
            assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                cause -> assertThat(cause.getSQLState()).isEqualTo("42501"));
        });
    }

    private static LocalDate today() { return LocalDate.now(ZoneId.of("Europe/Moscow")); }
    private static String shortId() { return UUID.randomUUID().toString().substring(0, 8); }

    private record MatrixFixture(ClientSummary client, ClientSummary archive, AccountSummary own, AccountSummary close,
                                 OperationSummary charge, OperationSummary draft, OperationSummary correction,
                                 UserSummary user, long businessAuditId, long securityAuditId) {}
    private record PostCommand(String login, OperationSummary draft, UUID key, String fingerprint) {}
}
