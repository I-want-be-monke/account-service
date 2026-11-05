package ru.tsu.accounts.report;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Report(ReportQuery query, Instant generatedAt, long totalRows, Map<String, String> totals,
                     List<ReportRow> rows) {
    public Report {
        totals = Collections.unmodifiableMap(new LinkedHashMap<>(totals));
        rows = List.copyOf(rows);
    }
}
