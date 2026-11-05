package ru.tsu.accounts.operation;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.account.*;
import ru.tsu.accounts.audit.*;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@Service
public class OperationService {
 private final OperationRepository operations;
 private final AccountRepository accounts;
 private final AccountService accountAccess;
 private final AuditService audit;
 public OperationService(OperationRepository operations,AccountRepository accounts,AccountService accountAccess,AuditService audit){
  this.operations=operations; this.accounts=accounts; this.accountAccess=accountAccess; this.audit=audit;
 }
 @Transactional(readOnly=true)
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public Page<OperationSummary> drafts(UUID accountId,int page){
  accountAccess.get(accountId);
  return operations.findByAccountIdAndStorageStatusAndCreatedBy(accountId,"PREPARED",principal().id(),page(page))
   .map(OperationSummary::from);
 }
 @Transactional(readOnly=true)
 @PreAuthorize("hasAnyRole('OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
 public OperationSummary get(UUID id){
  FinancialOperation op=operations.findById(id).orElseThrow(OperationService::missing);
  if(op.getStorageStatus().equals("PREPARED")
    && (!"ACCOUNTANT".equals(principal().role())||!op.getCreatedBy().equals(principal().id())))
   throw forbidden();
  accountAccess.get(op.getAccountId());
  return summary(op);
 }
 @Transactional(readOnly=true)
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public OperationForm editForm(UUID id){
  FinancialOperation op=ownedDraft(id);
  if("CORRECTION".equals(op.getKind()))throw conflict("Для исправления откройте форму исправления.");
  return OperationForm.from(op);
 }
 @Transactional(readOnly=true)
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public OperationSummary ownedDraftSummary(UUID id){return OperationSummary.from(ownedDraft(id));}
 @Transactional(readOnly=true)
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String readyFingerprint(UUID id){
  FinancialOperation op=ownedDraft(id);
  PersonalAccount account=accounts.findById(op.getAccountId()).orElseThrow(OperationService::missing);
  if(!"OPEN".equals(account.getStatus()))throw conflict("Проведение допускается только на открытом счете.");
  if("CORRECTION".equals(op.getKind())){
   FinancialOperation original=operations.findById(op.getCorrectsOperationId()).orElseThrow(OperationService::missing);
   if(!"POSTED".equals(original.getStorageStatus())||"CORRECTION".equals(original.getKind())
      ||!original.getAccountId().equals(op.getAccountId()))throw conflict("Исходная операция недоступна.");
   CorrectionRules.validate(CorrectionForm.from(op),account.getHistoryStartOn());
   if(operations.postedCorrectionCount(original.getId())!=op.getExpectedCorrectionCount())
    throw conflict("Исправление устарело. Создайте новое исправление.");
   CorrectionRules.Effect expected=CorrectionRules.calculate(original.getAmount(),original.getEffectSign(),
     operations.postedCorrectionEffect(original.getId()),operations.postedCorrectionCount(original.getId()),op.getTargetAmount());
   if(expected.correctionCount()!=op.getExpectedCorrectionCount()
       ||expected.delta().compareTo(op.getAmount().multiply(BigDecimal.valueOf(op.getEffectSign())))!=0)
    throw conflict("Исправление устарело. Создайте новое исправление.");
  }else MoneyRules.validate(OperationForm.from(op),account.getHistoryStartOn());
  return OperationFingerprint.of(op);
 }
 @Transactional
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public OperationSummary prepare(UUID accountId,OperationForm form,UUID actor,String login,UUID requestId){
  verifyActor(actor);
  PersonalAccount account=accounts.lockById(accountId).orElseThrow(OperationService::missing);
  if(!"OPEN".equals(account.getStatus()))throw conflict("Черновик можно создать только на открытом счете.");
  MoneyRules.DraftInput data=MoneyRules.validate(form,account.getHistoryStartOn());
  checkDocument(data);
  FinancialOperation draft=operations.saveAndFlush(new FinancialOperation(accountId,actor,data));
  audit.recordCriticalSuccess(event(actor,login,"OPERATION_PREPARED",draft,requestId,null,fields(draft)));
  return OperationSummary.from(draft);
 }
 @Transactional
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public OperationSummary edit(UUID id,OperationForm form,UUID actor,String login,UUID requestId){
  verifyActor(actor);
  UUID accountId=operations.findAccountIdById(id).orElseThrow(OperationService::missing);
  PersonalAccount account=accounts.lockById(accountId).orElseThrow(OperationService::missing);
  FinancialOperation draft=operations.lockById(id).orElseThrow(OperationService::missing);
  own(draft);
  if("CORRECTION".equals(draft.getKind()))throw conflict("Для исправления откройте форму исправления.");
  if(!"OPEN".equals(account.getStatus()))throw conflict("Счет сейчас не открыт.");
  if(form.getVersion()==null||!form.getVersion().equals(draft.getVersion()))throw conflict("Черновик изменен. Обновите страницу.");
  MoneyRules.DraftInput data=MoneyRules.validate(form,account.getHistoryStartOn());
  if(!draft.getKind().equals(data.kind()))throw conflict("Вид черновика менять нельзя.");
  checkDocument(data);
  Map<String,?> before=fields(draft);
  draft.edit(data);
  operations.flush();
  audit.recordCriticalSuccess(event(actor,login,"OPERATION_DRAFT_UPDATED",draft,requestId,before,fields(draft)));
  return OperationSummary.from(draft);
 }
 @Transactional
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public void discard(UUID id,long version,UUID actor,String login,UUID requestId){
  verifyActor(actor);
  UUID accountId=operations.findAccountIdById(id).orElseThrow(OperationService::missing);
  accounts.lockById(accountId).orElseThrow(OperationService::missing);
  FinancialOperation draft=operations.lockById(id).orElseThrow(OperationService::missing);
  own(draft);
  if(version!=draft.getVersion())throw conflict("Черновик изменен. Обновите страницу.");
  Map<String,?> before=fields(draft);
  operations.delete(draft); operations.flush();
  audit.recordCriticalSuccess(event(actor,login,"OPERATION_DRAFT_DISCARDED",draft,requestId,before,null));
 }
 private void checkDocument(MoneyRules.DraftInput data){
  if(data.externalNumberKey()!=null
    && operations.existsByExternalSourceAndKindAndExternalNumberKeyAndStorageStatus(
     data.externalSource(),data.kind(),data.externalNumberKey(),"POSTED"))
   throw conflict("Документ с таким номером уже проведен.");
 }
 private OperationSummary summary(FinancialOperation op){
  return OperationSummary.from(op,"POSTED".equals(op.getStorageStatus())
   &&!"CORRECTION".equals(op.getKind())
   &&operations.existsByCorrectsOperationIdAndStorageStatus(op.getId(),"POSTED"));
 }
 private FinancialOperation ownedDraft(UUID id){
  FinancialOperation draft=operations.findById(id).orElseThrow(OperationService::missing);
  own(draft); return draft;
 }
 private void own(FinancialOperation op){
  if(!"PREPARED".equals(op.getStorageStatus())||!op.getCreatedBy().equals(principal().id()))
   throw forbidden();
 }
 private void verifyActor(UUID actor){if(!principal().id().equals(actor))throw forbidden();}
 private AccountPrincipal principal(){
  return (AccountPrincipal)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
 }
 private static PageRequest page(int index){
  if(index<0||index>100000)throw new UserCommandException(HttpStatus.BAD_REQUEST,"INVALID_PAGE","Некорректная страница.");
  return PageRequest.of(index,20,Sort.by("operationDate").descending().and(Sort.by("id").descending()));
 }
 static Map<String,?> fields(FinancialOperation op){
  java.util.Map<String,Object> fields=new java.util.LinkedHashMap<>();
  fields.put("accountId",op.getAccountId().toString());fields.put("kind",op.getKind());
  fields.put("status",op.getStorageStatus());fields.put("date",op.getOperationDate().toString());
  fields.put("amount",op.getAmount().toPlainString());fields.put("basis",op.getBasis());
  fields.put("document",op.getExternalNumber());
  if("CORRECTION".equals(op.getKind())){
   fields.put("sourceId",op.getCorrectsOperationId().toString());
   fields.put("targetAmount",op.getTargetAmount().toPlainString());
   fields.put("reason",op.getCorrectionReason());
   fields.put("expectedCorrectionCount",op.getExpectedCorrectionCount());
  }
  return fields;
 }
 static AuditEntry event(UUID actor,String login,String action,FinancialOperation op,UUID requestId,
                         Map<String,?> before,Map<String,?> after){
  return new AuditEntry(actor,login,"USER","BUSINESS",action,"OPERATION",op.getId().toString(),
   "SUCCESS",null,requestId,before,after,Map.of("accountId",op.getAccountId().toString()));
 }
 static UserCommandException missing(){
  return new UserCommandException(HttpStatus.NOT_FOUND,"OPERATION_NOT_FOUND","Операция не найдена.");
 }
 static UserCommandException forbidden(){
  return new UserCommandException(HttpStatus.FORBIDDEN,"OPERATION_FORBIDDEN","Операция недоступна.");
 }
 public static UserCommandException conflict(String m){
  return new UserCommandException(HttpStatus.CONFLICT,"OPERATION_CONFLICT",m);
 }
}
