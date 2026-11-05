package ru.tsu.accounts.migration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MigrationValidationReport(String status, UUID runId, String sourceSha256,
        Map<String, Integer> counts, Map<String, String> controlBalances, String debt, String advance,
        List<Issue> issues) {

    public MigrationValidationReport {
        counts = Map.copyOf(counts);
        controlBalances = Map.copyOf(controlBalances);
        issues = List.copyOf(issues);
    }

    public boolean valid() { return !"INVALID".equals(status); }

    static MigrationValidationReport invalid(String sha256, List<Issue> issues) {
        return new MigrationValidationReport("INVALID", null, sha256, Map.of(), Map.of(), null, null, issues);
    }

    public record Issue(String file, long row, String message) {}
}
