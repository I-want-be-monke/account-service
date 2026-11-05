package ru.tsu.accounts.report;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.tsu.accounts.identity.UserCommandException;

class ReportServiceIT extends ReportIntegrationSupport {
    @Test
    void octoberMonthlyReportsSeparateOriginalsFromCorrectionsAndExcludeDrafts() {
        prepareCharge(ACCOUNT_C, OCTOBER_START.plusDays(10), "9999.00");
        var charges = report("CHARGES", OCTOBER);
        var payments = report("PAYMENTS", OCTOBER);

        assertThat(charges.rows()).extracting(ReportRow::accountId).containsExactly(ACCOUNT_A, ACCOUNT_B);
        assertThat(row(charges, ACCOUNT_A).amounts()).isEqualTo(Map.of(
            "originalCharges", "0.00", "chargeCorrections", "-100.00", "netCharges", "-100.00"));
        assertThat(charges.totals()).isEqualTo(Map.of(
            "originalCharges", "200.00", "chargeCorrections", "-100.00", "netCharges", "100.00"));
        assertThat(payments.rows()).extracting(ReportRow::accountId).containsExactly(ACCOUNT_A, ACCOUNT_B);
        assertThat(row(payments, ACCOUNT_A).amounts()).isEqualTo(Map.of(
            "originalPayments", "400.00", "paymentCorrections", "-50.00", "netPayments", "350.00"));
        assertThat(payments.totals()).isEqualTo(Map.of(
            "originalPayments", "900.00", "paymentCorrections", "-50.00", "netPayments", "850.00"));
        assertThat(report("CHARGES", Map.of("month", "2026-09")).totals()).isEqualTo(Map.of(
            "originalCharges", "1000.00", "chargeCorrections", "0.00", "netCharges", "1000.00"));

        // Independently classify each ledger line by the original document's kind, then sum signed effects.
        var netByOriginalKind = jdbc.queryForList("""
            SELECT coalesce(source.kind, movement.kind) AS original_kind, sum(movement.delta) AS effect
            FROM financial_operation movement
            LEFT JOIN financial_operation source ON source.id = movement.corrects_operation_id
            WHERE movement.storage_status = 'POSTED' AND movement.operation_date BETWEEN ? AND ?
            GROUP BY coalesce(source.kind, movement.kind)
            """, OCTOBER_START, OCTOBER_END);
        assertThat(netByOriginalKind).hasSize(2);
        for (var entry : netByOriginalKind) {
            var effect = (BigDecimal) entry.get("effect");
            if (entry.get("original_kind").equals("CHARGE"))
                assertThat(effect).isEqualByComparingTo(charges.totals().get("netCharges"));
            else {
                assertThat(entry.get("original_kind")).isEqualTo("PAYMENT");
                assertThat(effect.negate()).isEqualByComparingTo(payments.totals().get("netPayments"));
            }
        }
    }

    @Test
    void historicalDebtSeparatesDebtAdvancesAndZeroDespiteLaterPosting() {
        postDraft(prepareCharge(ACCOUNT_A, LocalDate.of(2026, 11, 1), "80.00"));
        assertThat(jdbc.queryForObject("SELECT current_balance FROM personal_account WHERE id = ?", BigDecimal.class,
            ACCOUNT_A)).isEqualByComparingTo("630.00");
        var october = report("DEBT", Map.of("asOf", OCTOBER_END.toString()));
        assertThat(october.totals()).isEqualTo(Map.of("debt", "550.00", "advance", "300.00", "balance", "250.00"));
        assertThat(row(october, ACCOUNT_A).amounts()).isEqualTo(Map.of("debt", "550.00", "advance", "0.00", "balance", "550.00"));
        assertThat(row(october, ACCOUNT_B).amounts()).isEqualTo(Map.of("debt", "0.00", "advance", "300.00", "balance", "-300.00"));
        assertThat(row(october, ACCOUNT_C).amounts()).isEqualTo(Map.of("debt", "0.00", "advance", "0.00", "balance", "0.00"));
        var september = report("DEBT", Map.of("asOf", "2026-09-30"));
        assertThat(september.totals()).isEqualTo(Map.of("debt", "1000.00", "advance", "0.00", "balance", "1000.00"));
        assertThat(report("DEBT", Map.of("asOf", "2026-11-01")).totals()).containsEntry("debt", "630.00");

        var sql = jdbc.queryForMap("""
            SELECT sum(greatest(historical_balance, 0)) AS debt,
                   sum(greatest(-historical_balance, 0)) AS advance,
                   sum(historical_balance) AS balance
            FROM (
                SELECT a.opening_balance + coalesce((
                    SELECT sum(delta) FROM financial_operation
                    WHERE account_id = a.id AND storage_status = 'POSTED'
                      AND operation_date BETWEEN a.history_start_on AND ?), 0) AS historical_balance
                FROM personal_account a WHERE a.history_start_on <= ?
            ) ledger
            """, OCTOBER_END, OCTOBER_END);
        october.totals().forEach((key, value) -> assertThat((BigDecimal) sql.get(key)).isEqualByComparingTo(value));
    }

    @Test
    void octoberMovementMatchesIndependentLedgerAndE8StatementsPerAccount() {
        long count = operationCount();
        var movement = report("MOVEMENT", OCTOBER_MOVEMENT);
        assertThat(movement.totals()).isEqualTo(Map.of("openingBalance", "1000.00", "originalCharges", "200.00",
            "originalPayments", "900.00", "corrections", "-50.00", "increase", "250.00", "decrease", "1000.00",
            "change", "-750.00", "closingBalance", "250.00"));
        assertThat(row(movement, ACCOUNT_A).amounts()).isEqualTo(Map.of("openingBalance", "1000.00",
            "originalCharges", "0.00", "originalPayments", "400.00", "corrections", "-50.00", "increase", "50.00",
            "decrease", "500.00", "change", "-450.00", "closingBalance", "550.00"));
        assertThat(row(movement, ACCOUNT_B).amounts()).isEqualTo(Map.of("openingBalance", "0.00",
            "originalCharges", "200.00", "originalPayments", "500.00", "corrections", "0.00", "increase", "200.00",
            "decrease", "500.00", "change", "-300.00", "closingBalance", "-300.00"));
        assertThat(row(movement, ACCOUNT_C).amounts()).allSatisfy((key, value) -> assertThat(value).isEqualTo("0.00"));
        for (var row : movement.rows()) {
            var statement = statements.generate(row.accountId(), "2026-10-01", "2026-10-31");
            assertThat(statement.openingBalance()).isEqualByComparingTo(row.amounts().get("openingBalance"));
            assertThat(statement.increase()).isEqualByComparingTo(row.amounts().get("increase"));
            assertThat(statement.decrease()).isEqualByComparingTo(row.amounts().get("decrease"));
            assertThat(statement.closingBalance()).isEqualByComparingTo(row.amounts().get("closingBalance"));
        }

        var sums = jdbc.queryForMap("""
            SELECT sum(delta) FILTER (WHERE operation_date < ?) AS prior_effect,
                   sum(delta) FILTER (WHERE operation_date BETWEEN ? AND ?) AS period_effect,
                   sum(delta) FILTER (WHERE operation_date <= ?) AS total_effect
            FROM financial_operation WHERE storage_status = 'POSTED'
            """, OCTOBER_START, OCTOBER_START, OCTOBER_END, OCTOBER_END);
        var base = jdbc.queryForObject("SELECT sum(opening_balance) FROM personal_account", BigDecimal.class);
        assertThat(base.add((BigDecimal) sums.get("prior_effect"))).isEqualByComparingTo(movement.totals().get("openingBalance"));
        assertThat((BigDecimal) sums.get("period_effect")).isEqualByComparingTo(movement.totals().get("change"));
        assertThat(base.add((BigDecimal) sums.get("total_effect"))).isEqualByComparingTo(movement.totals().get("closingBalance"));
        assertThat(operationCount()).isEqualTo(count);
    }

    @Test
    void paginationStableOrderingAndFiltersKeepTotalsOverTheEntireSelection() {
        var baseline = report("DEBT", Map.of("asOf", "2026-10-31"));
        var observed = new ArrayList<UUID>();
        for (int page = 0; page < 3; page++) {
            var result = report("DEBT", Map.of("asOf", "2026-10-31", "page", Integer.toString(page), "size", "1"));
            assertThat(result.rows()).hasSize(1);
            assertThat(result.totalRows()).isEqualTo(3);
            assertThat(result.totals()).isEqualTo(baseline.totals());
            observed.add(result.rows().getFirst().accountId());
        }
        assertThat(observed).containsExactly(ACCOUNT_A, ACCOUNT_B, ACCOUNT_C);
        assertThat(report("DEBT", Map.of("asOf", "2026-10-31", "page", "0", "size", "2")).rows())
            .extracting(ReportRow::accountId).containsExactly(ACCOUNT_A, ACCOUNT_B);
        var pastEnd = report("DEBT", Map.of("asOf", "2026-10-31", "page", "100000", "size", "1"));
        assertThat(pastEnd.rows()).isEmpty();
        assertThat(pastEnd.totalRows()).isEqualTo(3);
        assertThat(pastEnd.totals()).isEqualTo(baseline.totals());

        changeStatus(ACCOUNT_B, "SUSPENDED");
        changeStatus(ACCOUNT_C, "CLOSED");
        var selected = report("DEBT", Map.of("asOf", "2026-10-31", "clientId", CLIENT_ONE.toString(), "size", "1"));
        assertThat(selected.totalRows()).isEqualTo(2);
        assertThat(selected.totals()).isEqualTo(Map.of("debt", "550.00", "advance", "0.00", "balance", "550.00"));
        var suspended = report("DEBT", Map.of("asOf", "2026-10-31", "status", "suspended"));
        assertThat(suspended.rows()).extracting(ReportRow::accountId).containsExactly(ACCOUNT_B);
        assertThat(suspended.totals()).isEqualTo(Map.of("debt", "0.00", "advance", "300.00", "balance", "-300.00"));
        var closed = report("DEBT", Map.of("asOf", "2026-10-31", "status", "CLOSED", "clientId", CLIENT_ONE.toString()));
        assertThat(closed.rows()).extracting(ReportRow::accountId).containsExactly(ACCOUNT_C);
        assertThat(closed.totals()).allSatisfy((key, value) -> assertThat(value).isEqualTo("0.00"));
    }

    @Test
    void allReportTypesReturnZeroTotalsForAnEmptyHistoricalSelection() {
        var emptyReports = List.of(report("CHARGES", Map.of("month", "2026-08")),
            report("PAYMENTS", Map.of("month", "2026-08")), report("DEBT", Map.of("asOf", "2026-08-31")),
            report("MOVEMENT", Map.of("from", "2026-08-01", "to", "2026-08-31")),
            report("DEBT", Map.of("asOf", "2026-10-31", "clientId", UUID.randomUUID().toString())));
        for (var result : emptyReports) {
            assertThat(result.rows()).isEmpty();
            assertThat(result.totalRows()).isZero();
            assertThat(result.totals()).isNotEmpty().allSatisfy((key, value) -> assertThat(value).isEqualTo("0.00"));
        }
    }

    @Test
    void invalidReportParametersAreRejectedAnd366DaysAreAllowed() {
        invalid("UNKNOWN", Map.of());
        invalid("CHARGES", Map.of());
        invalid("CHARGES", Map.of("month", "2026-13"));
        invalid("PAYMENTS", Map.of("month", "2026-10-01"));
        invalid("DEBT", Map.of("asOf", "2026-02-29"));
        invalid("DEBT", Map.of("asOf", "0000-01-01"));
        invalid("DEBT", Map.of("asOf", "2026-10-31", "clientId", "1-1-1-1-1"));
        invalid("DEBT", Map.of("asOf", "2026-10-31", "status", "OPEN' OR 1=1"));
        invalid("DEBT", Map.of("asOf", "2026-10-31", "page", "-1"));
        invalid("DEBT", Map.of("asOf", "2026-10-31", "page", "100001"));
        invalid("DEBT", Map.of("asOf", "2026-10-31", "size", "0"));
        invalid("DEBT", Map.of("asOf", "2026-10-31", "size", "101"));
        invalid("MOVEMENT", Map.of("from", "2026-10-02", "to", "2026-10-01"));
        invalid("MOVEMENT", Map.of("from", "2026-01-01", "to", "2027-01-02"));
        assertThat(ReportQuery.parse("MOVEMENT", Map.of("from", "2026-01-01", "to", "2027-01-01")).to())
            .isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void snapshotKeepsSummaryAndCursorConsistentAcrossARealConcurrentPosting() throws Exception {
        var day = today();
        var pending = prepareCharge(ACCOUNT_A, day, "25.00");
        var parameters = Map.of("from", day.toString(), "to", day.toString(), "clientId", CLIENT_ONE.toString(), "size", "1");
        var query = ReportQuery.parse("MOVEMENT", parameters);
        var initial = report("MOVEMENT", parameters);
        var snapshotReady = new CountDownLatch(1);
        var writerCommitted = new CountDownLatch(1);
        var scanned = new ArrayList<ReportRow>();

        try (var workers = Executors.newFixedThreadPool(2)) {
            var reader = workers.submit(() -> {
                as("demo-manager");
                try {
                    return reports.scan(query, new ReportService.ReportSink() {
                        @Override public void start(Report metadata) {
                            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
                            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
                            snapshotReady.countDown();
                            await(writerCommitted);
                        }
                        @Override public void row(ReportRow row) { scanned.add(row); }
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            var writer = workers.submit(() -> {
                try {
                    await(snapshotReady);
                    return postDraft(pending);
                } finally {
                    writerCommitted.countDown();
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(writer.get(25, TimeUnit.SECONDS).id()).isEqualTo(pending.id());
            var snapshot = reader.get(25, TimeUnit.SECONDS);
            assertThat(snapshot.totals()).isEqualTo(initial.totals());
            assertThat(snapshot.totalRows()).isEqualTo(2);
            assertThat(scanned).extracting(ReportRow::accountId).containsExactly(ACCOUNT_A, ACCOUNT_C);
            assertThat(scanned.getFirst().amounts()).isEqualTo(initial.rows().getFirst().amounts());
        }
        var after = report("MOVEMENT", parameters);
        assertThat(after.totals()).containsEntry("originalCharges", "25.00");
        assertThat(new BigDecimal(after.totals().get("closingBalance")))
            .isEqualByComparingTo(new BigDecimal(initial.totals().get("closingBalance")).add(new BigDecimal("25.00")));
    }

    private static ReportRow row(Report report, UUID account) {
        return report.rows().stream().filter(row -> row.accountId().equals(account)).findFirst().orElseThrow();
    }

    private static void invalid(String type, Map<String, String> parameters) {
        assertThatThrownBy(() -> ReportQuery.parse(type, parameters))
            .isInstanceOfSatisfying(UserCommandException.class, exception -> {
                assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.reasonCode()).isEqualTo("REPORT_VALIDATION");
            });
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
