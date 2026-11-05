package ru.tsu.accounts.operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="financial_operation")
public class FinancialOperation {
 @Id private UUID id;
 @Column(name="account_id",nullable=false,updatable=false) private UUID accountId;
 @Column(nullable=false,updatable=false,length=16) private String kind;
 @Column(name="storage_status",nullable=false,length=16) private String storageStatus;
 @Column(name="operation_date",nullable=false) private LocalDate operationDate;
 @Column(nullable=false,precision=19,scale=2) private BigDecimal amount;
 @Column(name="effect_sign",nullable=false) private Short effectSign;
 @Column(insertable=false,updatable=false,precision=19,scale=2) private BigDecimal delta;
 @Column(name="period_start") private LocalDate periodStart;
 @Column(nullable=false,length=500) private String basis;
 @Column(name="payment_method",length=32) private String paymentMethod;
 @Column(name="external_source",length=64) private String externalSource;
 @Column(name="external_number",length=100) private String externalNumber;
 @Column(name="external_number_key",length=100) private String externalNumberKey;
 @Column(name="corrects_operation_id") private UUID correctsOperationId;
 @Column(name="target_amount",precision=19,scale=2) private BigDecimal targetAmount;
 @Column(name="correction_reason",length=1000) private String correctionReason;
 @Column(name="created_by",nullable=false,updatable=false) private UUID createdBy;
 @Column(name="created_at",insertable=false,updatable=false) private Instant createdAt;
 @Column(name="updated_at",insertable=false) private Instant updatedAt;
 @Column(name="posted_by") private UUID postedBy;
 @Column(name="posted_at") private Instant postedAt;
 @Column(name="idempotency_key") private UUID idempotencyKey;
 @JdbcTypeCode(SqlTypes.CHAR) @Column(name="request_fingerprint",length=64) private String requestFingerprint;
 @Version private Long version;
 protected FinancialOperation() {}
 FinancialOperation(UUID accountId, UUID author, MoneyRules.DraftInput input) {
  id=UUID.randomUUID(); this.accountId=accountId; createdBy=author; kind=input.kind();
  storageStatus="PREPARED"; apply(input);
 }
 void edit(MoneyRules.DraftInput input) {
  if (!"PREPARED".equals(storageStatus) || !kind.equals(input.kind())) throw new IllegalStateException("Immutable operation");
  apply(input);
 }
 private void apply(MoneyRules.DraftInput input) {
  operationDate=input.operationDate(); amount=input.amount();
  effectSign=(short)(kind.equals("CHARGE")?1:-1); periodStart=input.periodStart(); basis=input.basis();
  paymentMethod=input.paymentMethod(); externalSource=input.externalSource();
  externalNumber=input.externalNumber(); externalNumberKey=input.externalNumberKey();
  updatedAt=Instant.now();
 }
 void post(UUID author, UUID key, String fingerprint) {
  storageStatus="POSTED"; postedBy=author; postedAt=Instant.now();
  idempotencyKey=key; requestFingerprint=fingerprint; updatedAt=Instant.now();
 }
 public UUID getId(){return id;} public UUID getAccountId(){return accountId;}
 public String getKind(){return kind;} public String getStorageStatus(){return storageStatus;}
 public LocalDate getOperationDate(){return operationDate;} public BigDecimal getAmount(){return amount;}
 public Short getEffectSign(){return effectSign;} public BigDecimal getDelta(){return delta;}
 public LocalDate getPeriodStart(){return periodStart;} public String getBasis(){return basis;}
 public String getPaymentMethod(){return paymentMethod;} public String getExternalSource(){return externalSource;}
 public String getExternalNumber(){return externalNumber;} public String getExternalNumberKey(){return externalNumberKey;}
 public UUID getCreatedBy(){return createdBy;} public Instant getCreatedAt(){return createdAt;}
 public Instant getPostedAt(){return postedAt;} public UUID getIdempotencyKey(){return idempotencyKey;}
 public String getRequestFingerprint(){return requestFingerprint;} public Long getVersion(){return version;}
}
