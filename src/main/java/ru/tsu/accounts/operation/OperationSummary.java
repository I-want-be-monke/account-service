package ru.tsu.accounts.operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
public record OperationSummary(UUID id,UUID accountId,String kind,String status,LocalDate date,
 BigDecimal amount,BigDecimal delta,LocalDate periodStart,String basis,String paymentMethod,
 String documentNumber,long version,Instant postedAt) {
 public static OperationSummary from(FinancialOperation op) {
  return new OperationSummary(op.getId(),op.getAccountId(),op.getKind(),op.getStorageStatus(),
   op.getOperationDate(),op.getAmount(),op.getAmount().multiply(BigDecimal.valueOf(op.getEffectSign())),
   op.getPeriodStart(),op.getBasis(),op.getPaymentMethod(),op.getExternalNumber(),
   op.getVersion(),op.getPostedAt());
 }
}
