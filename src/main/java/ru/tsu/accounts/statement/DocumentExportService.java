package ru.tsu.accounts.statement;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.tsu.accounts.export.CsvFile;
import ru.tsu.accounts.export.CsvRows;

/** Writes one account-period cursor to a private temporary file, never the whole journal. */
@Service
public class DocumentExportService {
    public static final String COLUMNS = "record_type;format_version;account_id;account_number;owner_id;owner_name;"
        + "account_status;history_start_on;from;to;generated_at;currency;operation_id;operation_date;posted_at;"
        + "kind;status;document_number;basis;period_start;payment_method;original_operation_id;target_amount;"
        + "correction_reason;amount;delta;balance_after;opening_balance;increase;decrease;closing_balance";
    private final StatementService statements;

    public DocumentExportService(StatementService statements) { this.statements = statements; }

    public CsvFile<Statement> createCsv(UUID accountId, String from, String to) {
        return CsvFile.create("account-statement-", COLUMNS,
            writer -> statements.scan(accountId, from, to, new StatementService.StatementSink() {
                    private Statement header;
                    @Override public void start(Statement statement) {
                        header = statement;
                        CsvRows.write(writer, row("HEADER", statement, null));
                        CsvRows.write(writer, row("OPENING", statement, null));
                    }
                    @Override public void line(StatementLine line) {
                        CsvRows.write(writer, row("OPERATION", header, line));
                    }
                    @Override public void finish(Statement statement) {
                        CsvRows.write(writer, row("TOTAL", statement, null));
                    }
                }),
            metadata -> "statement-" + metadata.accountNumber() + "-" + metadata.from()
                + "-" + metadata.to() + ".csv");
    }

    private static String[] row(String kind, Statement s, StatementLine line) {
        String[] cells = new String[31];
        java.util.Arrays.fill(cells, "");
        text(cells, 0, kind); text(cells, 1, s.formatVersion()); text(cells, 2, s.accountId());
        text(cells, 11, s.currency());
        if ("HEADER".equals(kind)) {
            text(cells, 3, s.accountNumber()); text(cells, 4, s.ownerId()); text(cells, 5, s.ownerName());
            text(cells, 6, s.accountStatus()); text(cells, 7, s.historyStartOn());
            text(cells, 8, s.from()); text(cells, 9, s.to()); text(cells, 10, s.generatedAt());
        } else if ("OPENING".equals(kind)) {
            money(cells, 26, s.openingBalance()); money(cells, 27, s.openingBalance());
        } else if ("OPERATION".equals(kind)) {
            text(cells, 12, line.id()); text(cells, 13, line.date()); text(cells, 14, line.postedAt());
            text(cells, 15, line.kind()); text(cells, 16, line.status()); text(cells, 17, line.documentNumber());
            text(cells, 18, line.basis()); text(cells, 19, line.periodStart()); text(cells, 20, line.paymentMethod());
            text(cells, 21, line.originalOperationId()); money(cells, 22, line.targetAmount());
            text(cells, 23, line.correctionReason()); money(cells, 24, line.amount());
            money(cells, 25, line.delta()); money(cells, 26, line.balanceAfter());
        } else if ("TOTAL".equals(kind)) {
            money(cells, 27, s.openingBalance()); money(cells, 28, s.increase());
            money(cells, 29, s.decrease()); money(cells, 30, s.closingBalance());
        }
        return cells;
    }

    private static void money(String[] cells, int index, BigDecimal value) {
        cells[index] = CsvRows.money(value);
    }

    private static void text(String[] cells, int index, Object value) {
        cells[index] = CsvRows.text(value);
    }
}
