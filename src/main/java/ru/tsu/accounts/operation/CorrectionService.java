package ru.tsu.accounts.operation;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.account.AccountRepository;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.account.PersonalAccount;
import ru.tsu.accounts.audit.AuditService;
import ru.tsu.accounts.security.AccountPrincipal;

@Service
public class CorrectionService {
 private final OperationRepository operations;
 private final AccountRepository accounts;
 private final AccountService accountAccess;
 private final AuditService audit;
 public CorrectionService(OperationRepository operations,AccountRepository accounts,
                          AccountService accountAccess,AuditService audit){
  this.operations=operations;this.accounts=accounts;this.accountAccess=accountAccess;this.audit=audit;
 }
 @Transactional(readOnly=true)
 @PreAuthorize("hasAnyRole('OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
 public List<OperationSummary> postedFor(UUID originalId){
  FinancialOperation original=operations.findById(originalId).orElseThrow(OperationService::missing);
  accountAccess.get(original.getAccountId());
  if(!"POSTED".equals(original.getStorageStatus())||"CORRECTION".equals(original.getKind()))
   return List.of();
  return operations.findByCorrectsOperationIdAndStorageStatusOrderByPostedAtAscIdAsc(originalId,"POSTED")
   .stream().map(OperationSummary::from).toList();
 }
 @Transactional(readOnly=true)
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public OperationSummary source(UUID originalId){
  FinancialOperation original=original(originalId);
  accountAccess.get(original.getAccountId());
  return OperationSummary.from(original,operations.existsByCorrectsOperationIdAndStorageStatus(originalId,"POSTED"));
 }
 @Transactional(readOnly=true)
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public CorrectionForm editForm(UUID draftId){
  FinancialOperation draft=owned(draftId);
  accountAccess.get(draft.getAccountId());
  return CorrectionForm.from(draft);
 }
 @Transactional
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public OperationSummary prepare(UUID originalId,CorrectionForm form,UUID actor,String login,UUID requestId){
  verifyActor(actor);
  FinancialOperation original=original(originalId);
  PersonalAccount account=accounts.lockById(original.getAccountId()).orElseThrow(OperationService::missing);
  accountAccess.get(account.getId());
  requireOpen(account);
  CorrectionRules.Input input=CorrectionRules.validate(form,account.getHistoryStartOn());
  CorrectionRules.Effect effect=effect(original,input.target());
  FinancialOperation draft=operations.saveAndFlush(new FinancialOperation(account.getId(),actor,originalId,input,effect));
  audit.recordCriticalSuccess(OperationService.event(actor,login,"OPERATION_CORRECTION_PREPARED",draft,requestId,
   null,OperationService.fields(draft)));
  return OperationSummary.from(draft);
 }
 @Transactional
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public OperationSummary edit(UUID id,CorrectionForm form,UUID actor,String login,UUID requestId){
  verifyActor(actor);
  UUID accountId=operations.findAccountIdById(id).orElseThrow(OperationService::missing);
  PersonalAccount account=accounts.lockById(accountId).orElseThrow(OperationService::missing);
  FinancialOperation draft=operations.lockById(id).orElseThrow(OperationService::missing);
  if(!"PREPARED".equals(draft.getStorageStatus())||!"CORRECTION".equals(draft.getKind())
   ||!draft.getCreatedBy().equals(actor))throw OperationService.forbidden();
  requireOpen(account);
  if(form.getVersion()==null||!form.getVersion().equals(draft.getVersion()))
   throw OperationService.conflict("Черновик изменился. Обновите страницу.");
  FinancialOperation original=original(draft.getCorrectsOperationId());
  if(!original.getAccountId().equals(accountId))throw OperationService.forbidden();
  CorrectionRules.Input input=CorrectionRules.validate(form,account.getHistoryStartOn());
  CorrectionRules.Effect effect=effect(original,input.target());
  var before=OperationService.fields(draft);
  draft.editCorrection(input,effect);
  operations.flush();
  audit.recordCriticalSuccess(OperationService.event(actor,login,"OPERATION_CORRECTION_UPDATED",draft,requestId,
   before,OperationService.fields(draft)));
  return OperationSummary.from(draft);
 }
 private FinancialOperation original(UUID id){
  FinancialOperation op=operations.findById(id).orElseThrow(OperationService::missing);
  if(!"POSTED".equals(op.getStorageStatus())||"CORRECTION".equals(op.getKind()))
   throw OperationService.conflict("Исправлять можно только проведенное начисление или платеж.");
  return op;
 }
 private CorrectionRules.Effect effect(FinancialOperation original,BigDecimal target){
  return CorrectionRules.calculate(original.getAmount(),original.getEffectSign(),
   operations.postedCorrectionEffect(original.getId()),operations.postedCorrectionCount(original.getId()),target);
 }
 private FinancialOperation owned(UUID id){
  FinancialOperation op=operations.findById(id).orElseThrow(OperationService::missing);
  if(!"PREPARED".equals(op.getStorageStatus())||!"CORRECTION".equals(op.getKind())
   ||!op.getCreatedBy().equals(principal().id()))throw OperationService.forbidden();
  return op;
 }
 private void requireOpen(PersonalAccount account){
  if(!"OPEN".equals(account.getStatus()))throw OperationService.conflict("Исправление возможно только по открытому счету.");
 }
 private void verifyActor(UUID actor){if(!principal().id().equals(actor))throw OperationService.forbidden();}
 private AccountPrincipal principal(){return (AccountPrincipal)SecurityContextHolder.getContext().getAuthentication().getPrincipal();}
}
