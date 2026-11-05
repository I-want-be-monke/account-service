package ru.tsu.accounts.operation;
import java.time.LocalDate;
import java.time.ZoneId;
public class OperationForm {
 private String kind;
 private String operationDate=LocalDate.now(ZoneId.of("Europe/Moscow")).toString();
 private String amount;
 private String periodStart=LocalDate.now(ZoneId.of("Europe/Moscow")).withDayOfMonth(1).toString();
 private String basis;
 private String paymentMethod;
 private String externalNumber;
 private Long version;
 public String getKind(){return kind;} public void setKind(String v){kind=v;}
 public String getOperationDate(){return operationDate;} public void setOperationDate(String v){operationDate=v;}
 public String getAmount(){return amount;} public void setAmount(String v){amount=v;}
 public String getPeriodStart(){return periodStart;} public void setPeriodStart(String v){periodStart=v;}
 public String getBasis(){return basis;} public void setBasis(String v){basis=v;}
 public String getPaymentMethod(){return paymentMethod;} public void setPaymentMethod(String v){paymentMethod=v;}
 public String getExternalNumber(){return externalNumber;} public void setExternalNumber(String v){externalNumber=v;}
 public Long getVersion(){return version;} public void setVersion(Long v){version=v;}
 public static OperationForm from(FinancialOperation op) {
  OperationForm form=new OperationForm();
  form.kind=op.getKind(); form.operationDate=op.getOperationDate().toString();
  form.amount=op.getAmount().toPlainString();
  form.periodStart=op.getPeriodStart()==null?null:op.getPeriodStart().toString();
  form.basis=op.getBasis(); form.paymentMethod=op.getPaymentMethod();
  form.externalNumber=op.getExternalNumber(); form.version=op.getVersion();
  return form;
 }
}
