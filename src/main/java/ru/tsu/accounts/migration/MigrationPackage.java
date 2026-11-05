package ru.tsu.accounts.migration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record MigrationPackage(String sourceName, String sha256, List<Client> clients, List<Account> accounts,
        List<Operation> operations, Map<String, String> controlBalances, String debt, String advance) {

    Map<String, Integer> counts() {
        return Map.of("clients", clients.size(), "accounts", accounts.size(), "operations", operations.size());
    }

    MigrationValidationReport report(String status, UUID runId) {
        return new MigrationValidationReport(status, runId, sha256, counts(), controlBalances,
            debt, advance, List.of());
    }

    record Client(long row, UUID id, String type, String name, String identifierType, String identifier,
                  String key, String phone, String email, String status) {}

    record Account(long row, UUID id, String number, UUID clientId, String status, LocalDate openedOn,
                   LocalDate closedOn, LocalDate historyStartOn, BigDecimal openingBalance) {}

    record Operation(long row, UUID id, UUID accountId, String kind, LocalDate date, BigDecimal amount,
                     LocalDate period, String basis, String method, String number, UUID originalId,
                     BigDecimal target, String reason, BigDecimal delta, Long correctionCount) {}
}
