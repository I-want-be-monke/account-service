package ru.tsu.accounts.statement;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A posted movement; the signed delta is its actual effect on debt. */
public record StatementLine(
    UUID id,
    String kind,
    String status,
    LocalDate date,
    Instant postedAt,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal delta,
    String documentNumber,
    String basis,
    LocalDate periodStart,
    String paymentMethod,
    UUID originalOperationId,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal targetAmount,
    String correctionReason,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceAfter) {

    public StatementLine {
        amount = Statement.money(amount);
        delta = Statement.money(delta);
        targetAmount = targetAmount == null ? null : Statement.money(targetAmount);
        balanceAfter = Statement.money(balanceAfter);
    }
}
