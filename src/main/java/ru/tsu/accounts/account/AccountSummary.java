package ru.tsu.accounts.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AccountSummary(UUID id, String accountNumber, UUID clientId, String clientName,
                             String status, LocalDate openedOn, LocalDate closedOn,
                             LocalDate historyStartOn, BigDecimal openingBalance,
                             BigDecimal currentBalance, String currency, long version) {
    public static AccountSummary from(PersonalAccount account, String name) {
        return new AccountSummary(account.getId(), account.getAccountNumber(), account.getClientId(), name,
            account.getStatus(), account.getOpenedOn(), account.getClosedOn(), account.getHistoryStartOn(),
            account.getOpeningBalance(), account.getCurrentBalance(), account.getCurrency(), account.getVersion());
    }
}
