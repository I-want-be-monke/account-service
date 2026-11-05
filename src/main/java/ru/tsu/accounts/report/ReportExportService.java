package ru.tsu.accounts.report;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import ru.tsu.accounts.export.CsvFile;
import ru.tsu.accounts.export.CsvRows;

@Service
public class ReportExportService {
    private static final String HEADER = "record_type;format_version;report_type;from;to;generated_at;"
        + "currency;client_filter;status_filter;account_id;account_number;client_id;client_name;status";
    private final ReportService reports;

    public ReportExportService(ReportService reports) {
        this.reports = reports;
    }

    public CsvFile<Report> createCsv(ReportQuery query) {
        String columns = query.type().getColumns().stream().map(ReportType.Column::key)
            .collect(Collectors.joining(";"));
        return CsvFile.create("account-report-", HEADER + ";" + columns,
            writer -> reports.scan(query, new ReportService.ReportSink() {
                private Report metadata;

                @Override public void start(Report report) {
                    metadata = report;
                }

                @Override public void row(ReportRow detail) {
                    CsvRows.write(writer, cells(metadata, detail, detail.amounts()));
                }

                @Override public void finish(Report report) {
                    CsvRows.write(writer, cells(report, null, report.totals()));
                }
            }),
            report -> "report-" + report.query().type().name().toLowerCase(Locale.ROOT) + "-"
                + report.query().from() + "-" + report.query().to() + ".csv");
    }

    private static String[] cells(Report report, ReportRow detail, Map<String, String> amounts) {
        ReportQuery query = report.query();
        var cells = new ArrayList<String>(14 + amounts.size());
        cells.add(CsvRows.text(detail == null ? "TOTAL" : "DETAIL"));
        cells.add(CsvRows.text("1"));
        cells.add(CsvRows.text(query.type()));
        cells.add(CsvRows.text(query.from()));
        cells.add(CsvRows.text(query.to()));
        cells.add(CsvRows.text(report.generatedAt()));
        cells.add(CsvRows.text("RUB"));
        cells.add(CsvRows.text(query.clientId()));
        cells.add(CsvRows.text(query.status()));
        cells.add(detail == null ? "" : CsvRows.text(detail.accountId()));
        cells.add(detail == null ? "" : CsvRows.text(detail.accountNumber()));
        cells.add(detail == null ? "" : CsvRows.text(detail.clientId()));
        cells.add(detail == null ? "" : CsvRows.text(detail.clientName()));
        cells.add(detail == null ? "" : CsvRows.text(detail.status()));
        for (var column : query.type().getColumns()) {
            cells.add(amounts.get(column.key()));
        }
        return cells.toArray(String[]::new);
    }
}
