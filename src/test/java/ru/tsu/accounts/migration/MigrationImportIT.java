package ru.tsu.accounts.migration;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.report.ReportQuery;
import ru.tsu.accounts.report.ReportService;
import ru.tsu.accounts.security.AccountPrincipal;
import ru.tsu.accounts.security.DatabaseUserDetailsService;
import ru.tsu.accounts.statement.StatementService;
import ru.tsu.accounts.support.LocalPostgres;
import ru.tsu.accounts.support.PostgresContextLifecycle;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false"})
@ActiveProfiles("dev")
@Testcontainers
class MigrationImportIT extends PostgresContextLifecycle {
    private static final String ACTOR = "demo-accountant";
    private static final String APP_USER = "import_test_app";
    private static final String APP_PASSWORD = "test-import-runtime-password";
    private static final Path EXAMPLE = Path.of("migration", "demo-package");
    private static final List<String> CSV_FILES = List.of("clients.csv", "accounts.csv", "operations.csv");
    private static final List<String> BUSINESS_TABLES = List.of("client", "personal_account", "account_status_history",
        "financial_operation", "import_run", "app_user");
    private static final Map<String, Integer> COUNTS = Map.of("clients", 2, "accounts", 3, "operations", 6);
    private static final AtomicLong NEXT_NUMBER = new AtomicLong(10000);
    private static final Pattern DEMO_NUMBERS = Pattern.compile("LS-000000010([123])");

    @Container
    static final PostgreSQLContainer<?> postgres = LocalPostgres.create()
        .withDatabaseName("accounts").withUsername("account_owner")
        .withPassword("test-owner-password").withInitScript("db/import-test-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("account.demo.passwords.admin", () -> "correct-password-for-e1");
        registry.add("account.demo.passwords.operator", () -> "operator-password-for-e1");
        registry.add("account.demo.passwords.accountant", () -> "accountant-password-for-e1");
        registry.add("account.demo.passwords.manager", () -> "manager-password-for-e1");
        registry.add("account.demo.passwords.client", () -> "client-password-for-e1");
    }

    @Autowired MigrationImportService migration;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DatabaseUserDetailsService users;
    @Autowired StatementService statements;
    @Autowired ReportService reports;
    @Autowired AccountService accounts;
    @TempDir Path temporary;

    private JdbcTemplate ownerJdbc;

    @BeforeEach
    void verifyDisposableDatabaseAndUnprivilegedRuntime() {
        String connectedUrl = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        assertThat(connectedUrl).as("import test target is the disposable container").isEqualTo(postgres.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_USER);
        ownerJdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void trackedDemoDryRunValidatesChecksumsAndMultilineCsvWithoutWritingAnyRows() throws IOException {
        var before = snapshot(true);
        var manifest = readManifest(EXAMPLE);
        assertThat(canonicalHash(EXAMPLE)).isEqualTo(manifest.path("packageSha256").asText());
        var report = migration.dryRun(EXAMPLE, ACTOR);

        assertThat(report.status()).isEqualTo("VALID");
        assertThat(report.runId()).isNull();
        assertThat(report.sourceSha256()).isEqualTo(manifest.path("packageSha256").asText());
        assertThat(report.counts()).isEqualTo(COUNTS);
        assertThat(report.controlBalances()).isEqualTo(Map.of("LS-0000000101", "550.00",
            "LS-0000000102", "-300.00", "LS-0000000103", "0.00"));
        assertThat(report.debt()).isEqualTo("550.00");
        assertThat(report.advance()).isEqualTo("300.00");
        assertThat(report.issues()).isEmpty();
        assertThat(snapshot(true)).isEqualTo(before);
    }

    @Test
    void byteHashFormatCountsAndDeclaredFinancialControlsAreVerifiedBeforeWriting() throws IOException {
        var before = snapshot(true);
        var modifiedBytes = bundle("modified-bytes");
        Files.writeString(modifiedBytes.directory().resolve("clients.csv"), "\n", StandardCharsets.UTF_8,
            StandardOpenOption.APPEND);
        invalid(modifiedBytes, "clients.csv");

        var wrongHash = bundle("wrong-package-hash");
        var hashManifest = readManifest(wrongHash.directory());
        hashManifest.put("packageSha256", "0".repeat(64));
        writeManifest(wrongHash.directory(), hashManifest);
        invalid(wrongHash, "manifest.json");

        var wrongVersion = bundle("wrong-version");
        var versionManifest = readManifest(wrongVersion.directory());
        versionManifest.put("version", 2);
        writeManifest(wrongVersion.directory(), versionManifest);
        invalid(wrongVersion, "manifest.json");

        var wrongCount = bundle("wrong-count");
        var countManifest = readManifest(wrongCount.directory());
        ((ObjectNode) countManifest.path("files").path("clients.csv")).put("rows", 4);
        writeManifest(wrongCount.directory(), countManifest);
        invalid(wrongCount, "clients.csv");

        var wrongBalance = bundle("wrong-balance");
        var balanceManifest = readManifest(wrongBalance.directory());
        ((ObjectNode) balanceManifest.path("controlBalances")).put(wrongBalance.number(0), "549.99");
        writeManifest(wrongBalance.directory(), balanceManifest);
        invalidRecord(wrongBalance, "accounts.csv");
        assertThat(snapshot(true)).isEqualTo(before);
    }

    @Test
    void rejectsBrokenReferencesInvalidMoneyAndWrongCorrectionEffectBeforePostingAnything() throws IOException {
        var before = snapshot(true);
        var invalidScale = bundle("invalid-money-scale");
        replaceAndSeal(invalidScale, "operations.csv", ";400.00;;", ";400.001;;");
        invalidRecord(invalidScale, "operations.csv");

        var brokenClient = bundle("broken-client");
        replaceAndSeal(brokenClient, "accounts.csv", brokenClient.id(101).toString(), UUID.randomUUID().toString());
        invalidRecord(brokenClient, "accounts.csv");

        var brokenAccount = bundle("broken-account");
        replaceAndSeal(brokenAccount, "operations.csv", ";" + brokenAccount.id(201) + ";PAYMENT;",
            ";" + UUID.randomUUID() + ";PAYMENT;");
        invalidRecord(brokenAccount, "operations.csv");

        var badCorrection = bundle("incorrect-correction");
        replaceAndSeal(badCorrection, "operations.csv", ";900.00;Ошибка суммы;", ";901.00;Ошибка суммы;");
        invalidRecord(badCorrection, "operations.csv");

        var wrongCurrency = bundle("incorrect-currency");
        replaceAndSeal(wrongCurrency, "accounts.csv", ";RUB", ";USD");
        invalidRecord(wrongCurrency, "accounts.csv");

        var invalidClose = bundle("nonzero-closed-account");
        replaceAndSeal(invalidClose, "accounts.csv", ";SUSPENDED;2026-09-01;;", ";CLOSED;2026-09-01;2026-10-31;");
        invalidRecord(invalidClose, "accounts.csv");

        assertThat(snapshot(true)).isEqualTo(before);
        long successes = auditCount("IMPORT_APPLIED");
        assertThat(migration.apply(invalidScale.directory(), ACTOR).status()).isEqualTo("INVALID");
        assertThat(snapshot(false)).isEqualTo(withoutAudit(before));
        assertThat(auditCount("IMPORT_APPLIED")).isEqualTo(successes);
    }

    @Test
    void duplicateClientNumberAndDocumentAreRejectedWithinPackageAndAgainstDatabase() throws IOException {
        var before = snapshot(true);
        var duplicateClient = bundle("duplicate-client");
        replaceAndSeal(duplicateClient, "clients.csv", duplicateClient.key("DEMO-C2"),
            duplicateClient.key("DEMO-C1"));
        invalidRecord(duplicateClient, "clients.csv");

        var duplicateAccount = bundle("duplicate-account-number");
        replaceAndSeal(duplicateAccount, "accounts.csv", duplicateAccount.number(1), duplicateAccount.number(0));
        invalidRecord(duplicateAccount, "accounts.csv");

        var duplicateDocument = bundle("duplicate-document");
        replaceAndSeal(duplicateDocument, "operations.csv", duplicateDocument.key("PAY-B"),
            duplicateDocument.key("PAY-A"));
        invalidRecord(duplicateDocument, "operations.csv");
        assertThat(snapshot(true)).isEqualTo(before);

        var original = bundle("first-document-owner");
        assertThat(migration.apply(original.directory(), ACTOR).status()).isEqualTo("APPLIED");
        var imported = snapshot(true);
        var differentPackage = bundle("second-document-owner");
        replaceAndSeal(differentPackage, "operations.csv", differentPackage.key("PAY-B"), original.key("PAY-A"));
        invalidRecord(differentPackage, "operations.csv");
        assertThat(snapshot(true)).isEqualTo(imported);
    }

    @Test
    void successfulImportPreservesHistoryOwnershipAndAuditAndReplayIsReadOnly() throws IOException {
        var input = bundle("apply-and-replay");
        var beforeUsers = stateOf("app_user");
        long successBefore = auditCount("IMPORT_APPLIED");
        var preview = migration.dryRun(input.directory(), ACTOR);
        assertThat(preview.status()).isEqualTo("VALID");
        var applied = migration.apply(input.directory(), ACTOR);
        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.runId()).isNotNull();
        assertThat(applied.counts()).isEqualTo(COUNTS);
        assertThat(applied.controlBalances()).isEqualTo(preview.controlBalances());
        assertThat(applied.sourceSha256()).isEqualTo(preview.sourceSha256());
        assertThat(stateOf("app_user")).isEqualTo(beforeUsers);
        assertThat(auditCount("IMPORT_APPLIED")).isEqualTo(successBefore + 1);

        assertImportedLedger(input, applied.runId());
        var run = jdbc.queryForMap("""
            SELECT source_name, source_sha256, format_version, initiated_by, control_totals::text AS control_totals
            FROM import_run WHERE id = ?
            """, applied.runId());
        assertThat(run).containsEntry("source_name", input.label()).containsEntry("source_sha256", canonicalHash(input.directory()))
            .containsEntry("format_version", "1").containsEntry("initiated_by", principal(ACTOR).id());
        assertThat(run.get("control_totals").toString()).contains(input.number(0), "550.00", "300.00");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_event
            WHERE action = 'IMPORT_APPLIED' AND object_type = 'IMPORT_RUN' AND object_id = ?
              AND result = 'SUCCESS' AND actor_user_id = ?
            """, Long.class, applied.runId().toString(), principal(ACTOR).id())).isEqualTo(1);
        assertThat(ownerJdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
            .containsExactly("1", "2", "3", "4", "5", "6");

        assertThatThrownBy(() -> jdbc.update("UPDATE personal_account SET import_run_id = NULL WHERE id = ?", input.id(201)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertStatementAndReports(input);
        var after = snapshot(true);
        var replayPath = Files.createDirectory(temporary.resolve("another-directory-same-bytes"));
        for (String file : CSV_FILES) Files.copy(input.directory().resolve(file), replayPath.resolve(file));
        Files.copy(input.directory().resolve("manifest.json"), replayPath.resolve("manifest.json"));
        var replay = migration.apply(replayPath, ACTOR);
        assertThat(replay.status()).isEqualTo("ALREADY_APPLIED");
        assertThat(replay.runId()).isEqualTo(applied.runId());
        assertThat(replay.controlBalances()).isEqualTo(applied.controlBalances());
        assertThat(snapshot(true)).isEqualTo(after);
    }

    @Test
    void historicalOpeningBalanceRequiresCutoffAndIsCountedExactlyOnce() throws IOException {
        var carry = bundle("valid-opening-carry");
        replaceAndSeal(carry, "accounts.csv", ";SUSPENDED;2026-09-01;;2026-09-01;0.00;",
            ";SUSPENDED;2026-09-01;;2026-10-01;25.00;");
        updateAdvanceControl(carry, "-275.00", "275.00");
        var preview = migration.dryRun(carry.directory(), ACTOR);
        assertThat(preview.status()).isEqualTo("VALID");
        assertThat(preview.controlBalances()).containsEntry(carry.number(1), "-275.00");
        assertThat(migration.apply(carry.directory(), ACTOR).status()).isEqualTo("APPLIED");
        as("demo-manager");
        var statement = statements.generate(carry.id(202), "2026-10-01", "2026-10-31");
        assertThat(statement.openingBalance()).isEqualByComparingTo("25.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("-275.00");
        assertThat(statement.operations()).hasSize(2);

        var before = snapshot(true);
        var doubleCount = bundle("opening-carry-without-cutoff");
        replaceAndSeal(doubleCount, "accounts.csv", ";SUSPENDED;2026-09-01;;2026-09-01;0.00;",
            ";SUSPENDED;2026-09-01;;2026-09-01;25.00;");
        updateAdvanceControl(doubleCount, "-275.00", "275.00");
        invalidRecord(doubleCount, "accounts.csv");

        var beforeHistory = bundle("old-operation-outside-history");
        replaceAndSeal(beforeHistory, "accounts.csv", ";OPEN;2026-09-01;;2026-09-01;0.00;",
            ";OPEN;2026-09-01;;2026-10-01;0.00;");
        invalidRecord(beforeHistory, "operations.csv");
        assertThat(snapshot(true)).isEqualTo(before);
    }

    @Test
    void onlyAnActiveExistingAccountantCanExecuteImport() throws IOException {
        var input = bundle("actor-checks");
        jdbc.update("""
            INSERT INTO app_user(id, login, full_name, password_hash, role_code, status)
            SELECT gen_random_uuid(), 'blocked-import-test', 'Заблокированный исполнитель', password_hash,
                   'ACCOUNTANT', 'BLOCKED' FROM app_user WHERE login = ?
            """, ACTOR);
        var before = snapshot(true);
        for (String login : List.of("demo-admin", "missing-import-test", "blocked-import-test")) {
            var validation = migration.dryRun(input.directory(), login);
            assertThat(validation.status()).isEqualTo("INVALID");
            assertThat(validation.issues()).isNotEmpty();
            assertThat(migration.apply(input.directory(), login).status()).isEqualTo("INVALID");
        }
        assertThat(snapshot(true)).isEqualTo(before);
    }

    @Test
    void postgresFaultAfterPostedRowsRollsBackTheWholeImportIncludingRunAndSuccessAudit() throws IOException {
        var input = bundle("late-postgres-failure");
        var before = snapshot(false);
        long successes = auditCount("IMPORT_APPLIED");
        long rejections = auditCount("IMPORT_REJECTED");
        ownerJdbc.execute("CREATE SEQUENCE e10_test_fault_seen");
        ownerJdbc.execute("GRANT USAGE ON SEQUENCE e10_test_fault_seen TO account_runtime");
        ownerJdbc.execute("""
            CREATE FUNCTION e10_test_fail_after_import() RETURNS trigger LANGUAGE plpgsql AS $fault$
            BEGIN
                IF EXISTS (SELECT 1 FROM import_run WHERE id = NEW.object_id::uuid)
                   AND (SELECT count(*) FROM financial_operation
                        WHERE import_run_id = NEW.object_id::uuid AND storage_status = 'POSTED') = 6 THEN
                    PERFORM nextval('e10_test_fault_seen');
                END IF;
                RAISE EXCEPTION 'intentional E10 audit failure after staging ledger';
            END;
            $fault$
            """);
        ownerJdbc.execute("""
            CREATE TRIGGER e10_test_fail_import BEFORE INSERT ON audit_event FOR EACH ROW
            WHEN (NEW.action = 'IMPORT_APPLIED') EXECUTE FUNCTION e10_test_fail_after_import()
            """);
        try {
            var rejected = migration.apply(input.directory(), ACTOR);
            assertThat(rejected.status()).isEqualTo("INVALID");
            assertThat(rejected.runId()).isNull();
            assertThat(rejected.issues()).isNotEmpty();
            // PostgreSQL sequences are nontransactional, so this proves the fault fired after all six staged postings.
            assertThat(ownerJdbc.queryForObject("SELECT is_called FROM e10_test_fault_seen", Boolean.class)).isTrue();
            assertThat(snapshot(false)).isEqualTo(before);
            assertThat(auditCount("IMPORT_APPLIED")).isEqualTo(successes);
            assertThat(auditCount("IMPORT_REJECTED")).isEqualTo(rejections + 1);
        } finally {
            ownerJdbc.execute("DROP TRIGGER e10_test_fail_import ON audit_event");
            ownerJdbc.execute("DROP FUNCTION e10_test_fail_after_import()");
            ownerJdbc.execute("DROP SEQUENCE e10_test_fault_seen");
        }
        assertThat(migration.apply(input.directory(), ACTOR).status()).isEqualTo("APPLIED");
    }

    @Test
    void twoConcurrentApplicationsReturnTheSameRunAndOneSuccessAudit() throws Exception {
        var input = bundle("concurrent-import");
        long successes = auditCount("IMPORT_APPLIED");
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var attempts = new ArrayList<java.util.concurrent.Future<MigrationValidationReport>>();
            for (int index = 0; index < 2; index++) attempts.add(workers.submit(() -> {
                ready.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                return migration.apply(input.directory(), ACTOR);
            }));
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var first = attempts.get(0).get(30, TimeUnit.SECONDS);
            var second = attempts.get(1).get(30, TimeUnit.SECONDS);
            assertThat(List.of(first.status(), second.status())).containsExactlyInAnyOrder("APPLIED", "ALREADY_APPLIED");
            assertThat(first.runId()).isNotNull().isEqualTo(second.runId());
            assertThat(first.sourceSha256()).isEqualTo(second.sourceSha256());
            assertThat(countImported("personal_account", first.runId())).isEqualTo(3);
            assertThat(countImported("financial_operation", first.runId())).isEqualTo(6);
        }
        assertThat(auditCount("IMPORT_APPLIED")).isEqualTo(successes + 1);
    }

    @Test
    void normalAccountOpeningSkipsNumbersPreservedByTheImport() throws IOException {
        long sequenceValue = jdbc.queryForObject("SELECT nextval('personal_account_number_seq')", Long.class);
        var input = bundle("sequence-collisions", sequenceValue + 1);
        var applied = migration.apply(input.directory(), ACTOR);
        assertThat(applied.status()).isEqualTo("APPLIED");
        as("demo-operator");
        var operator = principal("demo-operator");
        var account = accounts.open(input.id(101), UUID.randomUUID(), LocalDate.now(ZoneId.of("Europe/Moscow")),
            operator.id(), operator.username(), UUID.randomUUID());
        assertThat(account.accountNumber()).isEqualTo(number(sequenceValue + 4));
        assertThat(account.currentBalance()).isEqualByComparingTo("0.00");
        assertThat(countImported("personal_account", applied.runId())).isEqualTo(3);
    }

    private void assertImportedLedger(Bundle input, UUID runId) {
        assertThat(countImported("client", runId)).isEqualTo(2);
        assertThat(countImported("personal_account", runId)).isEqualTo(3);
        assertThat(countImported("financial_operation", runId)).isEqualTo(6);
        var reconciliation = jdbc.queryForList("""
            SELECT p.account_number, p.client_id, c.import_run_id AS client_run, p.status, p.closed_on,
                   p.opening_balance, p.current_balance,
                   p.opening_balance + coalesce(sum(o.delta) FILTER (WHERE o.storage_status = 'POSTED'), 0) AS rebuilt
            FROM personal_account p JOIN client c ON c.id = p.client_id
            LEFT JOIN financial_operation o ON o.account_id = p.id
            WHERE p.import_run_id = ?
            GROUP BY p.id, c.id ORDER BY p.account_number
            """, runId);
        assertThat(reconciliation).hasSize(3);
        var owners = List.of(input.id(101), input.id(102), input.id(101));
        var statuses = List.of("OPEN", "SUSPENDED", "CLOSED");
        var balances = List.of("550.00", "-300.00", "0.00");
        for (int index = 0; index < reconciliation.size(); index++) {
            var account = reconciliation.get(index);
            assertThat(account).containsEntry("account_number", input.number(index)).containsEntry("client_id", owners.get(index))
                .containsEntry("client_run", runId).containsEntry("status", statuses.get(index));
            assertThat((BigDecimal) account.get("opening_balance")).isEqualByComparingTo("0.00");
            assertThat((BigDecimal) account.get("current_balance")).isEqualByComparingTo(balances.get(index));
            assertThat((BigDecimal) account.get("rebuilt")).isEqualByComparingTo(balances.get(index));
            assertThat(jdbc.queryForObject("""
                SELECT new_status FROM account_status_history WHERE account_id = ? ORDER BY changed_at DESC, id DESC LIMIT 1
                """, String.class, input.id(201 + index))).isEqualTo(statuses.get(index));
        }
        assertThat(reconciliation.getLast().get("closed_on").toString()).isEqualTo("2026-10-31");
        assertThat(jdbc.queryForObject("SELECT display_name FROM client WHERE id = ?", String.class, input.id(101)))
            .isEqualTo("Учебный клиент E10; \"А\"");
        assertThat(jdbc.queryForObject("SELECT basis FROM financial_operation WHERE id = ?", String.class, input.id(301)))
            .isEqualTo("Расчёт; \"сентябрь\"\nУчебная строка");
        assertThat(jdbc.queryForList("""
            SELECT corrects_operation_id FROM financial_operation
            WHERE import_run_id = ? AND kind = 'CORRECTION' ORDER BY operation_date
            """, UUID.class, runId)).containsExactly(input.id(301), input.id(302));
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM financial_operation WHERE import_run_id = ? AND storage_status = 'POSTED'
                AND created_by = ? AND posted_by = ?
            """, Long.class, runId, principal(ACTOR).id(), principal(ACTOR).id())).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM financial_operation
            WHERE import_run_id = ? AND external_number IS NOT NULL AND external_source = 'IMPORT'
            """, Long.class, runId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM (
                SELECT kind, external_number_key FROM financial_operation
                WHERE import_run_id = ? AND external_number_key IS NOT NULL
                GROUP BY kind, external_number_key HAVING count(*) > 1
            ) duplicates
            """, Long.class, runId)).isZero();
    }

    private void assertStatementAndReports(Bundle input) {
        as("demo-manager");
        var statement = statements.generate(input.id(201), "2026-10-01", "2026-10-31");
        assertThat(statement.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(statement.increase()).isEqualByComparingTo("50.00");
        assertThat(statement.decrease()).isEqualByComparingTo("500.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("550.00");
        assertThat(statement.operations()).hasSize(3);
        var clientOne = reports.generate(ReportQuery.parse("DEBT", Map.of("asOf", "2026-10-31", "clientId", input.id(101).toString())));
        var clientTwo = reports.generate(ReportQuery.parse("DEBT", Map.of("asOf", "2026-10-31", "clientId", input.id(102).toString())));
        assertThat(clientOne.totals()).isEqualTo(Map.of("debt", "550.00", "advance", "0.00", "balance", "550.00"));
        assertThat(clientTwo.totals()).isEqualTo(Map.of("debt", "0.00", "advance", "300.00", "balance", "-300.00"));
        assertThat(clientOne.totalRows()).isEqualTo(2);
        assertThat(clientTwo.totalRows()).isEqualTo(1);
    }

    private long countImported(String table, UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE import_run_id = ?", Long.class, runId);
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ?", Long.class, action);
    }

    private Map<String, String> snapshot(boolean includeAudit) {
        var snapshot = new LinkedHashMap<String, String>();
        for (String table : BUSINESS_TABLES) snapshot.put(table, stateOf(table));
        if (includeAudit) snapshot.put("audit_event", stateOf("audit_event"));
        return snapshot;
    }

    private static Map<String, String> withoutAudit(Map<String, String> snapshot) {
        var business = new LinkedHashMap<>(snapshot);
        business.remove("audit_event");
        return business;
    }

    private String stateOf(String table) {
        return jdbc.queryForObject("SELECT count(*)::text || ':' || md5(coalesce(string_agg(to_jsonb(t)::text, '' ORDER BY t.id), '')) FROM "
            + table + " t", String.class);
    }

    private AccountPrincipal principal(String login) {
        return (AccountPrincipal) users.loadUserByUsername(login);
    }

    private void as(String login) {
        var principal = principal(login);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private MigrationValidationReport invalid(Bundle input, String file) {
        var report = migration.dryRun(input.directory(), ACTOR);
        assertThat(report.status()).as("invalid fixture %s: %s", input.label(), report.issues()).isEqualTo("INVALID");
        assertThat(report.runId()).isNull();
        assertThat(report.issues()).extracting(MigrationValidationReport.Issue::file).contains(file);
        return report;
    }

    private void invalidRecord(Bundle input, String file) {
        assertThat(invalid(input, file).issues()).anySatisfy(issue -> {
            assertThat(issue.file()).isEqualTo(file);
            assertThat(issue.row()).isPositive();
        });
    }

    private Bundle bundle(String label) throws IOException {
        return bundle(label, NEXT_NUMBER.getAndAdd(10));
    }

    private Bundle bundle(String label, long firstNumber) throws IOException {
        var directory = Files.createDirectory(temporary.resolve(label));
        var bundle = new Bundle(directory, label, UUID.randomUUID().toString().substring(0, 8), firstNumber);
        for (String file : CSV_FILES) {
            String text = Files.readString(EXAMPLE.resolve(file), StandardCharsets.UTF_8)
                .replace("e1000000-0000-4000-8000-000000000", bundle.prefix() + "-0000-4000-8000-000000000")
                .replace("E10-", "E10-" + bundle.prefix() + "-");
            text = DEMO_NUMBERS.matcher(text).replaceAll(match -> bundle.number(Integer.parseInt(match.group(1)) - 1));
            Files.writeString(directory.resolve(file), text, StandardCharsets.UTF_8);
        }
        var manifest = readManifest(EXAMPLE);
        manifest.put("sourceName", label);
        manifest.set("controlBalances", json.valueToTree(Map.of(bundle.number(0), "550.00", bundle.number(1), "-300.00",
            bundle.number(2), "0.00")));
        seal(directory, manifest);
        return bundle;
    }

    private void replaceAndSeal(Bundle bundle, String file, String oldValue, String replacement) throws IOException {
        var path = bundle.directory().resolve(file);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(original).contains(oldValue);
        Files.writeString(path, original.replace(oldValue, replacement), StandardCharsets.UTF_8);
        seal(bundle.directory(), readManifest(bundle.directory()));
    }

    private void updateAdvanceControl(Bundle bundle, String balance, String advance) throws IOException {
        var manifest = readManifest(bundle.directory());
        ((ObjectNode) manifest.path("controlBalances")).put(bundle.number(1), balance);
        manifest.put("controlAdvance", advance);
        writeManifest(bundle.directory(), manifest);
    }

    private void seal(Path directory, ObjectNode manifest) throws IOException {
        for (String file : CSV_FILES) ((ObjectNode) manifest.path("files").path(file)).put("sha256", sha256(Files.readAllBytes(directory.resolve(file))));
        manifest.put("packageSha256", canonicalHash(directory));
        writeManifest(directory, manifest);
    }

    private ObjectNode readManifest(Path directory) throws IOException {
        return (ObjectNode) json.readTree(Files.readString(directory.resolve("manifest.json"), StandardCharsets.UTF_8));
    }

    private void writeManifest(Path directory, ObjectNode manifest) throws IOException {
        Files.writeString(directory.resolve("manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
            StandardCharsets.UTF_8);
    }

    private static String canonicalHash(Path directory) throws IOException {
        var canonical = new StringBuilder("account-service-import-v1\n");
        for (String file : CSV_FILES) canonical.append(file).append(':').append(sha256(Files.readAllBytes(directory.resolve(file)))).append('\n');
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String number(long serial) { return String.format(Locale.ROOT, "LS-%010d", serial); }

    private record Bundle(Path directory, String label, String prefix, long firstNumber) {
        UUID id(int tail) { return UUID.fromString(prefix + "-0000-4000-8000-000000000" + tail); }
        String number(int index) { return MigrationImportIT.number(firstNumber + index); }
        String key(String suffix) { return "E10-" + prefix + "-" + suffix; }
    }
}
