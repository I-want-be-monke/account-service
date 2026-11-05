package ru.tsu.accounts.web;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.audit.*;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.operation.*;
import ru.tsu.accounts.security.AccountPrincipal;

@Controller
public class OperationController {
 private static final Logger log=LoggerFactory.getLogger(OperationController.class);
 private final OperationService operations;
 private final HistoryQueryService history;
 private final CorrectionService corrections;
 private final OperationPostingService posting;
 private final OperationConfirmation confirmation;
 private final AccountService accounts;
 private final AuditAttemptService attempts;
 public OperationController(OperationService operations,HistoryQueryService history,CorrectionService corrections,OperationPostingService posting,
  OperationConfirmation confirmation,AccountService accounts,AuditAttemptService attempts){
  this.operations=operations;this.history=history;this.corrections=corrections;this.posting=posting;this.confirmation=confirmation;
  this.accounts=accounts;this.attempts=attempts;
 }
 @GetMapping("/accounts/{id}/operations")
 @PreAuthorize("hasAnyRole('OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
 public String list(@PathVariable UUID id,@RequestParam(defaultValue="") String from,
  @RequestParam(defaultValue="") String to,@RequestParam(defaultValue="") String kind,
  @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
  @AuthenticationPrincipal AccountPrincipal actor,Model model){
  model.addAttribute("account",accounts.get(id));
  model.addAttribute("posted",history.search(id,from,to,kind,page,size));
  if(actor.role().equals("ACCOUNTANT"))model.addAttribute("drafts",operations.drafts(id,page));
  model.addAttribute("from",from);model.addAttribute("to",to);
  model.addAttribute("kind",kind);model.addAttribute("size",size);
  model.addAttribute("isAccountant",actor.role().equals("ACCOUNTANT"));
  return "operations/list";
 }
 @GetMapping("/accounts/{id}/operations/new")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String newPage(@PathVariable UUID id,@RequestParam(defaultValue="CHARGE") String kind,Model model){
  model.addAttribute("account",accounts.get(id));
  if(!kind.equals("CHARGE")&&!kind.equals("PAYMENT"))throw OperationService.conflict("Неизвестный вид операции.");
  OperationForm form=new OperationForm();form.setKind(kind);
  model.addAttribute("form",form);model.addAttribute("edit",false);
  return "operations/form";
 }
 @PostMapping("/accounts/{id}/operations/new")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String create(@PathVariable UUID id,@ModelAttribute("form") OperationForm form,
  @AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request,HttpServletResponse response,Model model){
  try{
   var draft=operations.prepare(id,form,actor.id(),actor.username(),RequestIdFilter.id(request));
   return "redirect:/operations/"+draft.id()+"?created";
  }catch(UserCommandException e){
   denial(actor,request,"OPERATION_PREPARE_DENIED",id,e);
   response.setStatus(e.status().value());
   model.addAttribute("formError",e.getMessage());model.addAttribute("account",accounts.get(id));
   model.addAttribute("edit",false);return "operations/form";
  }
 }
 @GetMapping("/operations/{id}")
 @PreAuthorize("hasAnyRole('OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
 public String detail(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,Model model){
  var op=operations.get(id);
  model.addAttribute("operation","PREPARED".equals(op.status())?op:history.detail(id));
  model.addAttribute("account",accounts.get(op.accountId()));
  model.addAttribute("isAccountant",actor.role().equals("ACCOUNTANT"));
  model.addAttribute("corrections",corrections.postedFor(id));
  return "operations/detail";
 }
 @GetMapping("/operations/{id}/edit")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String editPage(@PathVariable UUID id,Model model){
  var op=operations.get(id);
  if("CORRECTION".equals(op.kind()))return "redirect:/operations/"+id+"/corrections/edit";
  model.addAttribute("form",operations.editForm(id));model.addAttribute("account",accounts.get(op.accountId()));
  model.addAttribute("edit",true);model.addAttribute("operationId",id);
  return "operations/form";
 }
 @PostMapping("/operations/{id}/edit")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String edit(@PathVariable UUID id,@ModelAttribute("form") OperationForm form,
  @AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request,HttpServletResponse response,Model model){
  try{
   operations.edit(id,form,actor.id(),actor.username(),RequestIdFilter.id(request));
   return "redirect:/operations/"+id+"?updated";
  }catch(UserCommandException e){
   denial(actor,request,"OPERATION_EDIT_DENIED",id,e);
   response.setStatus(e.status().value());
   model.addAttribute("formError",e.getMessage());
   model.addAttribute("account",accounts.get(operations.get(id).accountId()));
   model.addAttribute("edit",true);model.addAttribute("operationId",id);
   return "operations/form";
  }
 }
 @GetMapping("/operations/{id}/corrections/new")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String correctionNew(@PathVariable UUID id,Model model){
  var source=corrections.source(id);
  model.addAttribute("source",source);model.addAttribute("account",accounts.get(source.accountId()));
  model.addAttribute("form",new CorrectionForm());model.addAttribute("edit",false);
  return "operations/correction";
 }
 @PostMapping("/operations/{id}/corrections/new")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String correctionCreate(@PathVariable UUID id,@ModelAttribute("form") CorrectionForm form,
  @AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request,HttpServletResponse response,Model model){
  try{
   var draft=corrections.prepare(id,form,actor.id(),actor.username(),RequestIdFilter.id(request));
   return "redirect:/operations/"+draft.id()+"?created";
  }catch(UserCommandException e){
   denial(actor,request,"OPERATION_CORRECTION_PREPARE_DENIED",id,e);
   response.setStatus(e.status().value());
   model.addAttribute("formError",e.getMessage());
   var source=corrections.source(id);
   model.addAttribute("source",source);model.addAttribute("account",accounts.get(source.accountId()));
   model.addAttribute("edit",false);return "operations/correction";
  }
 }
 @GetMapping("/operations/{id}/corrections/edit")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String correctionEditPage(@PathVariable UUID id,Model model){
  CorrectionForm form=corrections.editForm(id);
  form.setOperationDate(java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).toString());
  var draft=operations.get(id);
  model.addAttribute("form",form);model.addAttribute("source",corrections.source(draft.correctsOperationId()));
  model.addAttribute("account",accounts.get(draft.accountId()));
  model.addAttribute("edit",true);model.addAttribute("operationId",id);
  return "operations/correction";
 }
 @PostMapping("/operations/{id}/corrections/edit")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String correctionEdit(@PathVariable UUID id,@ModelAttribute("form") CorrectionForm form,
  @AuthenticationPrincipal AccountPrincipal actor,HttpServletRequest request,HttpServletResponse response,Model model){
  try{
   corrections.edit(id,form,actor.id(),actor.username(),RequestIdFilter.id(request));
   return "redirect:/operations/"+id+"?updated";
  }catch(UserCommandException e){
   denial(actor,request,"OPERATION_CORRECTION_EDIT_DENIED",id,e);
   response.setStatus(e.status().value());
   var draft=operations.get(id);
   model.addAttribute("source",corrections.source(draft.correctsOperationId()));
   model.addAttribute("account",accounts.get(draft.accountId()));
   model.addAttribute("edit",true);model.addAttribute("operationId",id);
   model.addAttribute("formError",e.getMessage());return "operations/correction";
  }
 }
 @GetMapping("/operations/{id}/discard")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String discardPage(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,
  HttpSession session,Model model){
  var op=operations.ownedDraftSummary(id);
  model.addAttribute("operation",op);model.addAttribute("account",accounts.get(op.accountId()));
  model.addAttribute("pending",confirmation.prepare(session,actor.id(),id,"DISCARD",op.version(),""));
  return "operations/discard";
 }
 @PostMapping("/operations/{id}/discard")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String discard(@PathVariable UUID id,@RequestParam String token,
  @AuthenticationPrincipal AccountPrincipal actor,HttpSession session,HttpServletRequest request){
  try{
   var pending=confirmation.check(session,actor.id(),id,"DISCARD",token);
   UUID accountId=operations.get(id).accountId();
   operations.discard(id,pending.version(),actor.id(),actor.username(),RequestIdFilter.id(request));
   confirmation.clear(session);
   return "redirect:/accounts/"+accountId+"/operations?discarded";
  }catch(UserCommandException e){
   denial(actor,request,"OPERATION_DISCARD_DENIED",id,e);throw e;
  }
 }
 @GetMapping("/operations/{id}/confirm")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String confirmPage(@PathVariable UUID id,@AuthenticationPrincipal AccountPrincipal actor,
  HttpSession session,Model model){
  String fingerprint=operations.readyFingerprint(id);
  var op=operations.get(id);
  var pending=confirmation.prepare(session,actor.id(),id,"POST",op.version(),fingerprint);
  model.addAttribute("operation",op);model.addAttribute("account",accounts.get(op.accountId()));
  model.addAttribute("pending",pending);
  return "operations/confirm";
 }
 @PostMapping("/operations/{id}/confirm")
 @PreAuthorize("hasRole('ACCOUNTANT')")
 public String confirm(@PathVariable UUID id,@RequestParam String token,
  @AuthenticationPrincipal AccountPrincipal actor,HttpSession session,HttpServletRequest request){
  try{
   var pending=confirmation.check(session,actor.id(),id,"POST",token);
   posting.post(id,pending.version(),pending.idempotencyKey(),pending.fingerprint(),
    actor.id(),actor.username(),RequestIdFilter.id(request));
   return "redirect:/operations/"+id+"?posted";
  }catch(UserCommandException e){
   denial(actor,request,"OPERATION_POST_DENIED",id,e);throw e;
  }
 }
 private void denial(AccountPrincipal actor,HttpServletRequest request,String action,UUID id,UserCommandException e){
  try{
   attempts.record(AuditEntry.denied(actor.id(),actor.username(),"BUSINESS",action,
    "OPERATION",id.toString(),e.reasonCode(),RequestIdFilter.id(request)));
  }catch(Exception failure){
   log.error("Operation denial audit unavailable; requestId={}",RequestIdFilter.id(request),failure);
  }
 }
}
