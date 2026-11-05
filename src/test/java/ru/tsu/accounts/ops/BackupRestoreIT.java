package ru.tsu.accounts.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import ru.tsu.accounts.support.LocalPostgres;
import ru.tsu.accounts.support.PostgresContextLifecycle;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false",
    "spring.profiles.active=restore-test"})
@AutoConfigureMockMvc
@Testcontainers
class BackupRestoreIT extends PostgresContextLifecycle {
    private static final String PASSWORD = "restore-test-user-password";
    private static final String ARCHIVE = "/tmp/operations-test.dump";
    private static final String ACCOUNT = "e9000000-0000-4000-8000-000000000201";
    private static final String COUNTS = """
        SELECT (SELECT count(*) FROM app_user) AS users, (SELECT count(*) FROM client) AS clients,
               (SELECT count(*) FROM personal_account) AS accounts,
               (SELECT count(*) FROM financial_operation) AS operations,
               (SELECT count(*) FROM audit_event WHERE action = 'OPS_ARCHIVE_CHECK') AS events
        """;

    @Container static final PostgreSQLContainer<?> source = postgres("accounts");
    @Container static final PostgreSQLContainer<?> target = postgres("account_service_restore_test");
    private static Map<String, Object> sourceCounts;

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private static PostgreSQLContainer<?> postgres(String database) {
        return LocalPostgres.create().withDatabaseName(database).withUsername("account_owner")
            .withPassword("test-owner-password").withInitScript("db/test-roles.sql");
    }

    @DynamicPropertySource
    static void restoredDatabase(DynamicPropertyRegistry registry) throws Exception {
        var sourceData = new DriverManagerDataSource(source.getJdbcUrl(), source.getUsername(), source.getPassword());
        Flyway.configure().dataSource(sourceData).target("5").load().migrate();
        var original = new JdbcTemplate(sourceData);
        seed(original);
        sourceCounts = original.queryForMap(COUNTS);
        ok(source, "pg_dump", "-Fc", "-U", source.getUsername(), "-d", source.getDatabaseName(), "-f", ARCHIVE,
            "--exclude-table-data=public.spring_session", "--exclude-table-data=public.spring_session_attributes");
        ok(source, "pg_restore", "--exit-on-error", "--file=/dev/null", ARCHIVE);

        Path downloaded = Files.createTempFile("account-service-ops-", ".dump");
        try {
            source.copyFileFromContainer(ARCHIVE, downloaded.toString());
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(downloaded)));
            assertThat(ok(source, "sha256sum", ARCHIVE)).startsWith(hash + " ");
            target.copyFileToContainer(MountableFile.forHostPath(downloaded), ARCHIVE);
            assertThat(ok(target, "sha256sum", ARCHIVE)).startsWith(hash + " ");
        } finally {
            Files.deleteIfExists(downloaded);
        }

        ok(target, "pg_restore", "--exit-on-error", "--single-transaction", "-U", target.getUsername(),
            "-d", target.getDatabaseName(), ARCHIVE);
        target.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts", "restore-check.sql")), "/tmp/check.sql");
        assertThat(ok(target, "psql", "-XqAt", "-U", target.getUsername(), "-d", target.getDatabaseName(),
            "-f", "/tmp/check.sql")).contains("\"reconciledAccounts\" : 3");

        registry.add("spring.datasource.url", target::getJdbcUrl);
        registry.add("spring.datasource.username", target::getUsername);
        registry.add("spring.datasource.password", target::getPassword);
        registry.add("spring.flyway.user", target::getUsername);
        registry.add("spring.flyway.password", target::getPassword);
    }

    private static void seed(JdbcTemplate jdbc) throws IOException {
        String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
        jdbc.update("""
            INSERT INTO app_user(id,login,full_name,password_hash,role_code)
            VALUES (gen_random_uuid(),'demo-accountant','Ops accountant',?,'ACCOUNTANT'),
                   (gen_random_uuid(),'ops-admin','Ops administrator',?,'ADMIN')
            """, hash, hash);
        jdbc.execute("""
            INSERT INTO audit_event(actor_type,category,action,result,request_id)
            VALUES ('SYSTEM','OPERATIONS','OPS_ARCHIVE_CHECK','SUCCESS',gen_random_uuid());
            INSERT INTO spring_session VALUES
                ('70100000-0000-0000-0000-000000000001','70100000-0000-0000-0000-000000000002',
                 1000,1000,3600,4100000000000,'ops-admin');
            INSERT INTO spring_session_attributes VALUES
                ('70100000-0000-0000-0000-000000000001','ops-marker','\\x01');
            """);
        String fixture = new ClassPathResource("db/report-control.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute("BEGIN; SET LOCAL accounts.e9_fixture = 'isolated'; " + fixture + " COMMIT;");
    }

    @Test
    void restoredE9UpgradesAndServesTheSameStatementReportAndAudit() throws Exception {
        assertThat(jdbc.queryForMap(COUNTS)).isEqualTo(sourceCounts);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
            .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
        var login = mvc.perform(post("/login").secure(true).with(csrf())
                .param("username", "ops-admin").param("password", PASSWORD))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/")).andReturn();
        String setCookie = login.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("ACCOUNT_SESSION=");
        Cookie cookie = new Cookie("ACCOUNT_SESSION", setCookie.split("ACCOUNT_SESSION=", 2)[1].split(";", 2)[0]);
        mvc.perform(get("/api/v1/accounts/{id}/statement", ACCOUNT).secure(true).cookie(cookie)
                .param("from", "2026-10-01").param("to", "2026-10-31"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.openingBalance").value("1000.00"))
            .andExpect(jsonPath("$.closingBalance").value("550.00"));
        mvc.perform(get("/api/v1/reports/DEBT").secure(true).cookie(cookie).param("asOf", "2026-10-31"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totals.debt").value("550.00"))
            .andExpect(jsonPath("$.totals.advance").value("300.00"));
        mvc.perform(get("/audit").secure(true).cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void rejectsMissingDatabaseDamagedArchiveAndNonTestTarget() throws Exception {
        assertThat(source.execInContainer("pg_dump", "-Fc", "-U", source.getUsername(), "-d", "missing_ops_test").getExitCode())
            .isNotZero();
        ok(source, "sh", "-c", "head -c 24 /tmp/operations-test.dump > /tmp/truncated.dump");
        assertThat(source.execInContainer("pg_restore", "--exit-on-error", "--file=/dev/null", "/tmp/truncated.dump").getExitCode())
            .isNotZero();
        source.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts", "restore-check.sql")), "/tmp/check.sql");
        var wrongTarget = source.execInContainer("psql", "-XqAt", "-U", source.getUsername(),
            "-d", source.getDatabaseName(), "-f", "/tmp/check.sql");
        assertThat(wrongTarget.getExitCode()).isNotZero();
        assertThat(wrongTarget.getStderr()).contains("restore check must run in the isolated test database");
    }

    private static String ok(PostgreSQLContainer<?> container, String... command) throws Exception {
        var result = container.execInContainer(command);
        assertThat(result.getExitCode()).as("%s: %s", command[0], result.getStderr()).isZero();
        return result.getStdout();
    }
}
