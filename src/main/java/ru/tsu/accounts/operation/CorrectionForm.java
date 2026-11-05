package ru.tsu.accounts.operation;

import java.time.LocalDate;
import java.time.ZoneId;

public class CorrectionForm {
 private String operationDate=LocalDate.now(ZoneId.of("Europe/Moscow")).toString();
 private String targetAmount;
 private String reason;
 private String basis;
 private Long version;
 public String getOperationDate(){return operationDate;} public void setOperationDate(String value){operationDate=value;}
 public String getTargetAmount(){return targetAmount;} public void setTargetAmount(String value){targetAmount=value;}
 public String getReason(){return reason;} public void setReason(String value){reason=value;}
 public String getBasis(){return basis;} public void setBasis(String value){basis=value;}
 public Long getVersion(){return version;} public void setVersion(Long value){version=value;}
 public static CorrectionForm from(FinancialOperation op){
  CorrectionForm form=new CorrectionForm();
  form.operationDate=op.getOperationDate().toString();
  form.targetAmount=op.getTargetAmount().toPlainString();
  form.reason=op.getCorrectionReason();form.basis=op.getBasis();form.version=op.getVersion();
  return form;
 }
}
