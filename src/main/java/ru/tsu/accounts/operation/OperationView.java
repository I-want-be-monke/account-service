package ru.tsu.accounts.operation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Only public accounting fields of a posted movement. */
public record OperationView(UUID id,UUID accountId,String kind,String status,LocalDate date,
 BigDecimal amount,BigDecimal delta,LocalDate periodStart,String basis,String paymentMethod,
 String documentNumber,Instant postedAt,UUID correctsOperationId,BigDecimal targetAmount,
 String correctionReason) {}
