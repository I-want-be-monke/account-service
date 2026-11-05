package ru.tsu.accounts.operation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
public final class OperationFingerprint {
 private OperationFingerprint() {}
 public static String of(FinancialOperation op) {
  StringBuilder b=new StringBuilder();
  for(String field:new String[]{op.getAccountId().toString(),op.getKind(),op.getOperationDate().toString(),
   op.getAmount().toPlainString(),op.getEffectSign().toString(),
   op.getPeriodStart()==null?null:op.getPeriodStart().toString(),op.getBasis(),
   op.getPaymentMethod(),op.getExternalSource(),op.getExternalNumberKey(),op.getExternalNumber(),
   op.getCorrectsOperationId()==null?null:op.getCorrectsOperationId().toString(),
   op.getTargetAmount()==null?null:op.getTargetAmount().toPlainString(),
   op.getCorrectionReason(),op.getExpectedCorrectionCount()==null?null:op.getExpectedCorrectionCount().toString()}) {
   if(field==null)b.append("-1:");
   else b.append(field.length()).append(':').append(field);
  }
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
    .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
  } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
 }
}
