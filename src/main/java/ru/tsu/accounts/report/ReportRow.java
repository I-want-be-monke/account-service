package ru.tsu.accounts.report;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ReportRow(UUID accountId, String accountNumber, UUID clientId, String clientName,
                        String status, Map<String, String> amounts) {
    public ReportRow {
        amounts = Collections.unmodifiableMap(new LinkedHashMap<>(amounts));
    }
}
