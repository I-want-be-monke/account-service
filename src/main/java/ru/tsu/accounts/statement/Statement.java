package ru.tsu.accounts.statement;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The public document projection shared by HTML, JSON and CSV. */
public record Statement(
    String formatVersion,
    UUID accountId,
    String accountNumber,
    UUID ownerId,
    String ownerName,
    String accountStatus,
    LocalDate historyStartOn,
    LocalDate from,
    LocalDate to,
    Instant generatedAt,
    String currency,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalance,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal increase,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal decrease,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal closingBalance,
    List<StatementLine> operations) {

    public Statement {
        openingBalance = money(openingBalance);
        increase = money(increase);
        decrease = money(decrease);
        closingBalance = money(closingBalance);
        operations = List.copyOf(operations);
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    Statement withOperations(List<StatementLine> lines) {
        return new Statement(formatVersion, accountId, accountNumber, ownerId, ownerName,
            accountStatus, historyStartOn, from, to, generatedAt, currency,
            openingBalance, increase, decrease, closingBalance, lines);
    }
}
