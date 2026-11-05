package ru.tsu.accounts.web;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.report.ReportQuery;

final class ReportWebParameters {
    private ReportWebParameters() {}

    static Map<String, String> singleValues(MultiValueMap<String, String> parameters) {
        if (parameters.values().stream().anyMatch(values -> values.size() != 1)) {
            throw new UserCommandException(HttpStatus.BAD_REQUEST, "REPORT_VALIDATION",
                "Укажите каждый параметр отчёта один раз.");
        }
        return new LinkedHashMap<>(parameters.toSingleValueMap());
    }

    static Map<String, String> filters(ReportQuery query) {
        Map<String, String> filters = new LinkedHashMap<>();
        switch (query.type()) {
            case CHARGES, PAYMENTS -> filters.put("month", YearMonth.from(query.from()).toString());
            case DEBT -> filters.put("asOf", query.to().toString());
            case MOVEMENT -> {
                filters.put("from", query.from().toString());
                filters.put("to", query.to().toString());
            }
        }
        if (query.clientId() != null) filters.put("clientId", query.clientId().toString());
        if (query.status() != null) filters.put("status", query.status());
        return filters;
    }

    static long totalPages(long totalRows, int size) {
        return totalRows == 0 ? 0 : (totalRows - 1) / size + 1;
    }
}
