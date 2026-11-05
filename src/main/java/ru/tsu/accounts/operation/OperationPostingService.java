package ru.tsu.accounts.operation;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.account.*;
import ru.tsu.accounts.audit.AuditService;
import ru.tsu.accounts.security.AccountPrincipal;

@Service
public class OperationPostingService {
 private final OperationRepository operations;
 private final AccountRepository accounts;
 private final AuditService audit;
 public OperationPostingService(OperationRepository operations,AccountRepository accounts,AuditService audit){
  this.operations=operations;this.accounts=accounts;this.audit=audit;
 }
 @Transactional
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public OperationSummary post(UUID draftId,long version,UUID key,String expectedFingerprint,
                              UUID actor,String login,UUID requestId){
  if(draftId==null||key==null||expectedFingerprint==null||!principal().id().equals(actor))
   throw OperationService.forbidden();
  FinancialOperation prior=operations.findByIdempotencyKey(key).orElse(null);
  if(prior!=null){
   if(prior.getId().equals(draftId)&&prior.getCreatedBy().equals(actor)
       &&prior.getRequestFingerprint().equals(expectedFingerprint))
    return OperationSummary.from(prior);
   throw OperationService.conflict("Ключ запроса уже использован с другими реквизитами.");
  }
  UUID accountId=operations.findAccountIdById(draftId).orElseThrow(OperationService::missing);
  PersonalAccount account=accounts.lockById(accountId).orElseThrow(OperationService::missing);
  FinancialOperation completed=operations.findByIdempotencyKey(key).orElse(null);
  if(completed!=null){
   if(completed.getId().equals(draftId)&&completed.getCreatedBy().equals(actor)
      &&completed.getRequestFingerprint().equals(expectedFingerprint))return OperationSummary.from(completed);
   throw OperationService.conflict("Ключ запроса уже использован с другими реквизитами.");
  }
  FinancialOperation draft=operations.lockById(draftId).orElseThrow(OperationService::missing);
  if(!"PREPARED".equals(draft.getStorageStatus())||!draft.getCreatedBy().equals(actor))
   throw OperationService.conflict("Черновик уже проведен или недоступен.");
  if(!"OPEN".equals(account.getStatus()))throw OperationService.conflict("Счет закрыт или приостановлен.");
  if(version!=draft.getVersion()||!OperationFingerprint.of(draft).equals(expectedFingerprint))
   throw OperationService.conflict("Черновик изменен. Повторите подтверждение.");
  MoneyRules.validate(OperationForm.from(draft),account.getHistoryStartOn());
  if(draft.getExternalNumberKey()!=null
   &&operations.existsByExternalSourceAndKindAndExternalNumberKeyAndStorageStatus(
    draft.getExternalSource(),draft.getKind(),draft.getExternalNumberKey(),"POSTED"))
   throw OperationService.conflict("Документ с таким номером уже проведен.");
  BigDecimal delta=draft.getAmount().multiply(BigDecimal.valueOf(draft.getEffectSign()));
  BigDecimal oldBalance=account.getCurrentBalance();
  BigDecimal newBalance=MoneyRules.nextBalance(oldBalance,delta);
  draft.post(actor,key,expectedFingerprint);
  operations.flush();
  account.applyPostedBalance(newBalance);
  accounts.flush();
  audit.recordCriticalSuccess(new ru.tsu.accounts.audit.AuditEntry(actor,login,"USER","BUSINESS",
   "OPERATION_POSTED","OPERATION",draftId.toString(),"SUCCESS",null,requestId,
   Map.of("status","PREPARED","balance",oldBalance.toPlainString()),
   Map.of("status","POSTED","balance",newBalance.toPlainString(),"delta",delta.toPlainString()),
   Map.of("accountId",account.getId().toString(),"kind",draft.getKind(),
    "document",draft.getExternalNumber()==null?"":draft.getExternalNumber())));
  return OperationSummary.from(draft);
 }
 private AccountPrincipal principal(){
  return (AccountPrincipal)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
 }
}
