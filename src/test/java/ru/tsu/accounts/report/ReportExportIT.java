package ru.tsu.accounts.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditWriter;

class ReportExportIT extends ReportIntegrationSupport {
    private static final String DEBT_PERIOD = "asOf=2026-10-31";
    private static final String DEBT_QUERY = "?type=DEBT&" + DEBT_PERIOD;
    private static final List<String> CSV_METADATA = List.of("record_type", "format_version", "report_type",
        "from", "to", "generated_at", "currency", "client_filter", "status_filter", "account_id",
        "account_number", "client_id", "client_name", "status");

    @Autowired ObjectMapper json;
    @MockitoSpyBean AuditWriter auditWriter;
    @MockitoSpyBean ReportAuditService reportAudit;

    @Test
    void fourJsonContractsAndTheirHtmlPrintAndFullCsvAgreeOnTheStableLedger() throws Exception {
        for (var sample : List.of(
            new Sample("CHARGES", "month=2026-10", "2026-10-01", 2,
                Map.of("originalCharges", "200.00", "chargeCorrections", "-100.00", "netCharges", "100.00")),
            new Sample("PAYMENTS", "month=2026-10", "2026-10-01", 2,
                Map.of("originalPayments", "900.00", "paymentCorrections", "-50.00", "netPayments", "850.00")),
            new Sample("DEBT", DEBT_PERIOD, "2026-10-31", 3,
                Map.of("debt", "550.00", "advance", "300.00", "balance", "250.00")),
            new Sample("MOVEMENT", "from=2026-10-01&to=2026-10-31", "2026-10-01", 3,
                Map.of("openingBalance", "1000.00", "originalCharges", "200.00", "originalPayments", "900.00",
                    "corrections", "-50.00", "increase", "250.00", "decrease", "1000.00", "change", "-750.00",
                    "closingBalance", "250.00")))) {
            var htmlQuery = "?type=" + sample.type() + "&" + sample.period() + "&page=0&size=1";
            var html = getReport("/reports/result" + htmlQuery, "demo-admin");
            var print = getReport("/reports/print" + htmlQuery, "demo-admin");
            var api = getReport("/api/v1/reports/" + sample.type() + "?" + sample.period() + "&page=0&size=1", "demo-admin");
            var export = getReport("/reports.csv" + htmlQuery, "demo-admin");
            var dto = json.readTree(api.getResponse().getContentAsByteArray());
            assertThat(fieldNames(dto)).containsExactlyInAnyOrder("type", "parameters", "generatedAt", "currency",
                "totalRows", "totalPages", "page", "size", "columns", "totals", "rows");
            assertThat(dto.path("type").asText()).isEqualTo(sample.type());
            assertThat(dto.path("currency").asText()).isEqualTo("RUB");
            assertThat(dto.path("page").asInt()).isZero();
            assertThat(dto.path("size").asInt()).isEqualTo(1);
            assertThat(dto.path("totalRows").asInt()).isEqualTo(sample.totalRows());
            assertThat(dto.path("totalPages").asInt()).isEqualTo(sample.totalRows());
            assertThat(Instant.parse(dto.path("generatedAt").asText())).isNotNull();
            assertThat(dto.path("parameters").toString()).doesNotContain("page", "size");
            assertThat(dto.path("rows").size()).isEqualTo(1);
            var first = dto.path("rows").get(0);
            assertThat(fieldNames(first)).containsExactlyInAnyOrder("accountId", "accountNumber", "clientId",
                "clientName", "status", "amounts", "accountUrl");
            assertThat(first.path("accountId").asText()).isEqualTo(ACCOUNT_A.toString());
            assertThat(first.path("clientId").asText()).isEqualTo(CLIENT_ONE.toString());
            assertThat(first.path("accountUrl").asText()).isEqualTo("/accounts/" + ACCOUNT_A);
            assertThat(dto.toString()).doesNotContain("PRIVATE_E9", "e9-c1@example.invalid", "+7-000-000-00-01",
                "password", "SecurityContext", "authVersion", "requestFingerprint", "idempotencyKey");

            var csv = readCsv(export.getResponse().getContentAsByteArray());
            var expectedHeader = new ArrayList<>(CSV_METADATA);
            dto.path("columns").forEach(column -> expectedHeader.add(column.path("key").asText()));
            assertThat(csv.columns()).containsExactlyElementsOf(expectedHeader);
            assertThat(csv.records()).hasSize(sample.totalRows() + 1);
            var total = csv.records().getLast();
            assertThat(total).containsEntry("record_type", "TOTAL").containsEntry("account_id", "");
            assertThat(csv.records().subList(0, sample.totalRows())).allSatisfy(row -> {
                assertThat(row).containsEntry("record_type", "DETAIL").containsEntry("format_version", "1")
                    .containsEntry("report_type", sample.type()).containsEntry("currency", "RUB")
                    .containsEntry("from", sample.from()).containsEntry("to", "2026-10-31");
            });
            var csvFirst = csv.records().getFirst();
            assertThat(csvFirst.get("account_id")).isEqualTo(first.path("accountId").asText());
            assertThat(csvFirst.get("account_number")).isEqualTo(first.path("accountNumber").asText());
            assertThat(csvFirst.get("client_name")).isEqualTo(first.path("clientName").asText());
            var page = body(html);
            var printed = body(print);
            for (String document : List.of(page, printed)) {
                assertThat(document).contains("data-account-id=\"" + ACCOUNT_A + "\"")
                    .doesNotContain("data-account-id=\"" + ACCOUNT_B + "\"", "data-account-id=\"" + ACCOUNT_C + "\"");
            }
            assertThat(printed).contains("/css/print.css");
            assertThat(fieldNames(dto.path("totals"))).isEqualTo(sample.totals().keySet());
            for (var amount : sample.totals().entrySet()) {
                var money = dto.path("totals").path(amount.getKey());
                assertThat(money.isTextual()).isTrue();
                assertThat(money.asText()).isEqualTo(amount.getValue());
                assertThat(total.get(amount.getKey())).isEqualTo(money.asText());
                String totalCell = "data-total-key=\"" + amount.getKey() + "\">\\s*<strong>"
                    + Pattern.quote(money.asText()) + "</strong>";
                assertThat(page).containsPattern(totalCell);
                assertThat(printed).containsPattern(totalCell);

                var rowMoney = first.path("amounts").path(amount.getKey());
                assertThat(rowMoney.isTextual()).isTrue();
                assertThat(csvFirst.get(amount.getKey())).isEqualTo(rowMoney.asText());
                assertThat(page).containsPattern("data-amount-key=\"" + amount.getKey() + "\">\\s*"
                    + Pattern.quote(rowMoney.asText()) + "\\s*</td>");
            }
            assertThat(export.getResponse().getContentType()).startsWith("text/csv");
            assertThat(export.getResponse().getHeader("Content-Disposition")).contains("attachment", ".csv");
            for (var response : List.of(html, print, api, export))
                assertThat(response.getResponse().getHeader("Cache-Control")).contains("no-store");
        }
    }

    @Test
    void csvPreservesMultilineQuotedTextAndMakesFormulaPrefixesLiteral() throws Exception {
        for (String name : List.of("\u00a0\u200b=SUM(1;2)\r\n\"кавычки\"", "+cmd;\"quoted\"\nnext",
            "-2+3;\"строка\"\rnext", "\t @formula;\"text\"\r\nlast")) {
            // Legacy text permitted by PostgreSQL is the difficult export input. Normal client forms reject controls.
            jdbc.update("UPDATE client SET display_name = ? WHERE id = ?", name, CLIENT_TWO);
            var response = getReport("/reports.csv" + DEBT_QUERY + "&clientId=" + CLIENT_TWO, "demo-accountant");
            var bytes = response.getResponse().getContentAsByteArray();
            assertThat(bytes).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
            var records = readCsv(bytes).records();
            assertThat(records).hasSize(2);
            assertThat(records.getFirst()).containsEntry("client_name", "'" + name)
                .containsEntry("account_id", ACCOUNT_B.toString()).containsEntry("balance", "-300.00");
            assertThat(records.getLast()).containsEntry("record_type", "TOTAL").containsEntry("advance", "300.00");
            assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"\"");
        }
    }

    @Test
    void allReportRoutesEnforceTheRoleMatrixAndRefuseUnauthenticatedApiAccess() throws Exception {
        var paths = List.of("/reports", "/reports/result" + DEBT_QUERY, "/reports/print" + DEBT_QUERY,
            "/reports.csv" + DEBT_QUERY, "/api/v1/reports/DEBT?" + DEBT_PERIOD);
        for (String login : List.of("demo-admin", "demo-accountant", "demo-manager"))
            for (String path : paths) getReport(path, login);

        for (String login : List.of("demo-operator", "demo-client")) {
            for (String path : paths) {
                var denied = mvc.perform(get(path).secure(true).with(authentication(auth(login))))
                    .andExpect(status().isForbidden()).andReturn();
                assertThat(body(denied)).doesNotContain("550.00", "300.00", accountNumber(ACCOUNT_A),
                    "E9 — контрольный клиент C1", "record_type", "totalRows");
                assertThat(denied.getResponse().getHeader("Content-Disposition")).isNull();
                if (path.startsWith("/api/")) {
                    assertThat(denied.getResponse().getContentType()).startsWith("application/problem+json");
                    var problem = json.readTree(denied.getResponse().getContentAsByteArray());
                    assertThat(problem.path("code").asText()).isNotBlank();
                    assertThat(problem.path("requestId").asText()).isNotBlank();
                }
            }
        }
        mvc.perform(get("/api/v1/reports/DEBT?" + DEBT_PERIOD).secure(true).with(anonymous()))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/reports").secure(true).with(anonymous())).andExpect(status().is3xxRedirection());
        as("demo-operator");
        assertThatThrownBy(() -> reports.generate(ReportQuery.parse("DEBT", Map.of("asOf", "2026-10-31"))))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void managerReadsReportsButCannotCreateOrConfirmAFinancialDocumentEvenWithCsrf() throws Exception {
        var pending = prepareCharge(ACCOUNT_A, today(), "70.00");
        long count = operationCount();
        getReport("/api/v1/reports/DEBT?" + DEBT_PERIOD, "demo-manager");

        mvc.perform(post("/accounts/" + ACCOUNT_A + "/operations/new").secure(true)
            .with(authentication(auth("demo-manager"))).with(csrf()).param("kind", "CHARGE")
            .param("amount", "80.00").param("operationDate", today().toString())
            .param("periodStart", today().withDayOfMonth(1).toString()).param("basis", "Контроль доступа"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/operations/" + pending.id() + "/confirm").secure(true)
            .with(authentication(auth("demo-manager"))).with(csrf()).param("token", UUID.randomUUID().toString()))
            .andExpect(status().isForbidden());
        assertThat(operationCount()).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT storage_status FROM financial_operation WHERE id = ?", String.class,
            pending.id())).isEqualTo("PREPARED");
        assertThat(jdbc.queryForObject("SELECT current_balance FROM personal_account WHERE id = ?", BigDecimal.class,
            ACCOUNT_A)).isEqualByComparingTo("550.00");
    }

    @Test
    void directMalformedRequestsReturnSafeValidationErrors() throws Exception {
        for (String path : List.of("/api/v1/reports/UNKNOWN", "/api/v1/reports/CHARGES",
            "/api/v1/reports/PAYMENTS?month=2026-13", "/api/v1/reports/DEBT?asOf=2026-02-29",
            "/api/v1/reports/MOVEMENT?from=2026-10-31&to=2026-10-01",
            "/api/v1/reports/MOVEMENT?from=2026-01-01&to=2027-01-02",
            "/api/v1/reports/DEBT?" + DEBT_PERIOD + "&clientId=1-1-1-1-1",
            "/api/v1/reports/DEBT?" + DEBT_PERIOD + "&size=101",
            "/api/v1/reports/DEBT?" + DEBT_PERIOD + "&page=-1")) {
            assertBadRequest(get(path).secure(true).with(authentication(auth("demo-admin"))));
        }
        assertBadRequest(get("/api/v1/reports/DEBT?" + DEBT_PERIOD).secure(true)
            .with(authentication(auth("demo-admin"))).param("status", "OPEN' OR 1=1"));
        assertBadRequest(get("/api/v1/reports/CHARGES").secure(true)
            .with(authentication(auth("demo-admin"))).param("month", "2026-10", "2026-11"));
        for (String path : List.of("/reports/result", "/reports/print", "/reports.csv")) {
            var response = mvc.perform(get(path + "?type=DEBT&asOf=2026-02-29").secure(true)
                .with(authentication(auth("demo-admin")))).andExpect(status().isBadRequest()).andReturn();
            assertThat(body(response)).doesNotContain("record_type", "SQLException", "550.00");
            assertThat(response.getResponse().getHeader("Content-Disposition")).isNull();
        }
    }

    @Test
    void auditCommitsSeparatelyAndFailureReturns503BeforeAnyReportData() throws Exception {
        var audited = new AtomicInteger();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(reportAudit).record(any(Report.class), any(String.class), any(UUID.class));
        doAnswer(call -> {
            AuditEntry entry = call.getArgument(0);
            if ("REPORT_GENERATED".equals(entry.action())) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("off");
                audited.incrementAndGet();
            }
            return call.callRealMethod();
        }).when(auditWriter).insert(any(AuditEntry.class));
        var paths = List.of("/reports/result" + DEBT_QUERY, "/reports/print" + DEBT_QUERY,
            "/reports.csv" + DEBT_QUERY, "/api/v1/reports/DEBT?" + DEBT_PERIOD);
        var formats = List.of("HTML", "PRINT", "CSV", "JSON");
        for (int index = 0; index < paths.size(); index++) {
            var response = getReport(paths.get(index), "demo-admin");
            var requestId = UUID.fromString(response.getResponse().getHeader("X-Request-ID"));
            assertThat(jdbc.queryForObject("""
                SELECT details->>'format' FROM audit_event
                WHERE request_id = ? AND action = 'REPORT_GENERATED' AND object_type = 'REPORT' AND result = 'SUCCESS'
                """, String.class, requestId)).isEqualTo(formats.get(index));
        }
        assertThat(audited.get()).isEqualTo(4);

        doAnswer(call -> {
            AuditEntry entry = call.getArgument(0);
            if ("REPORT_GENERATED".equals(entry.action()))
                throw new DataAccessResourceFailureException("simulated report audit outage");
            return call.callRealMethod();
        }).when(auditWriter).insert(any(AuditEntry.class));
        for (String path : paths) {
            var response = mvc.perform(get(path).secure(true).with(authentication(auth("demo-admin"))))
                .andExpect(status().isServiceUnavailable()).andReturn();
            assertThat(body(response)).doesNotContain("\ufeff", "record_type", "550.00", "250.00", accountNumber(ACCOUNT_A));
            assertThat(response.getResponse().getHeader("Content-Disposition")).isNull();
        }
    }

    @Test
    void csvIncludesAll303AccountsBeyondFetchWindowDespiteRequestedPageSizeOne() throws Exception {
        jdbc.update("""
            INSERT INTO personal_account(id, account_number, client_id, status, opened_on, history_start_on,
                                         opening_balance, current_balance, currency)
            SELECT gen_random_uuid(), 'LS-' || lpad(nextval('personal_account_number_seq')::text, 10, '0'),
                   ?, 'OPEN', date '2026-09-01', date '2026-09-01', 0, 0, 'RUB'
            FROM generate_series(1, 300)
            """, CLIENT_ONE);
        var paged = getReport("/api/v1/reports/DEBT?" + DEBT_PERIOD + "&page=2&size=1", "demo-admin");
        var dto = json.readTree(paged.getResponse().getContentAsByteArray());
        assertThat(dto.path("rows").size()).isEqualTo(1);
        assertThat(dto.path("totalRows").asInt()).isEqualTo(303);
        var export = getReport("/reports.csv" + DEBT_QUERY + "&page=2&size=1", "demo-admin");
        var records = readCsv(export.getResponse().getContentAsByteArray()).records();
        assertThat(records).hasSize(304);
        var distinctAccounts = new HashSet<String>();
        for (var record : records.subList(0, 303)) {
            assertThat(record.get("record_type")).isEqualTo("DETAIL");
            assertThat(distinctAccounts.add(record.get("account_id"))).isTrue();
        }
        assertThat(distinctAccounts).contains(ACCOUNT_A.toString(), ACCOUNT_B.toString(), ACCOUNT_C.toString());
        assertThat(records.getLast()).containsEntry("record_type", "TOTAL").containsEntry("debt", "550.00")
            .containsEntry("advance", "300.00").containsEntry("balance", "250.00");
    }

    private MvcResult getReport(String path, String login) throws Exception {
        return mvc.perform(get(path).secure(true).with(authentication(auth(login))))
            .andExpect(status().isOk()).andReturn();
    }

    private void assertBadRequest(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("REPORT_VALIDATION"))
            .andExpect(jsonPath("$.requestId").isNotEmpty()).andReturn();
        assertThat(body(response)).doesNotContain("SQLException", "java.lang", "OPEN' OR", "totalRows", "550.00");
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Set<String> fieldNames(JsonNode node) {
        var fields = new HashSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private record Sample(String type, String period, String from, int totalRows, Map<String, String> totals) {}
    private record Csv(List<String> columns, List<Map<String, String>> records) {}

    /** Reads the downloaded dialect without using the application's CSV writer or splitting physical lines. */
    private static Csv readCsv(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertThat(content).startsWith("\ufeff");
        var records = new ArrayList<List<String>>();
        var record = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 1; index < content.length(); index++) {
            char character = content.charAt(index);
            if (quoted) {
                if (character == '"' && index + 1 < content.length() && content.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else if (character == '"') {
                    quoted = false;
                } else cell.append(character);
            } else if (character == '"') {
                assertThat(cell).isEmpty();
                quoted = true;
            } else if (character == ';') {
                record.add(cell.toString());
                cell.setLength(0);
            } else if (character == '\r' || character == '\n') {
                if (character == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
                record.add(cell.toString());
                cell.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else cell.append(character);
        }
        assertThat(quoted).isFalse();
        assertThat(record).isEmpty();
        assertThat(cell).isEmpty();
        var columns = records.removeFirst();
        var values = new ArrayList<Map<String, String>>();
        for (var fields : records) {
            assertThat(fields).hasSameSizeAs(columns);
            var row = new LinkedHashMap<String, String>();
            for (int index = 0; index < columns.size(); index++) row.put(columns.get(index), fields.get(index));
            values.add(row);
        }
        return new Csv(columns, values);
    }
}
