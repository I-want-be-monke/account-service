package ru.tsu.accounts.web;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.account.AccountSummary;
import ru.tsu.accounts.operation.HistoryQueryService;
import ru.tsu.accounts.operation.OperationView;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountReadApiController {
 private final AccountService accounts;
 private final HistoryQueryService history;
 public AccountReadApiController(AccountService accounts,HistoryQueryService history){
  this.accounts=accounts;this.history=history;
 }
 @GetMapping("/{id}")
 @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
 public AccountReadView account(@PathVariable UUID id){
  return AccountReadView.from(accounts.get(id));
 }
 @GetMapping("/{id}/operations")
 @PreAuthorize("hasAnyRole('OPERATOR','ACCOUNTANT','MANAGER','CLIENT')")
 public OperationPageView operations(@PathVariable UUID id,
  @RequestParam(defaultValue="") String from,@RequestParam(defaultValue="") String to,
  @RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="0") int page,
  @RequestParam(defaultValue="20") int size){
  var result=history.search(id,from,to,kind,page,size);
  var account=accounts.get(id);
  return new OperationPageView(account.id().toString(),account.currentBalance().toPlainString(),
   account.currency(),result.getNumber(),result.getSize(),result.getTotalElements(),
   result.getTotalPages(),result.getContent().stream().map(OperationReadView::from).toList());
 }
 public record AccountReadView(String id,String number,String status,String openedOn,String closedOn,
                               String historyStartOn,String currency,String balance){
  static AccountReadView from(AccountSummary a){
   return new AccountReadView(a.id().toString(),a.accountNumber(),a.status(),
    a.openedOn().toString(),a.closedOn()==null?null:a.closedOn().toString(),
    a.historyStartOn().toString(),a.currency(),a.currentBalance().toPlainString());
  }
 }
 public record OperationReadView(String id,String kind,String status,String date,String amount,
  String delta,String basis,String documentNumber,String originalOperationId,String targetAmount,
  String correctionReason){
  static OperationReadView from(OperationView o){
   return new OperationReadView(o.id().toString(),o.kind(),o.status(),o.date().toString(),
    o.amount().toPlainString(),o.delta().toPlainString(),o.basis(),o.documentNumber(),
    o.correctsOperationId()==null?null:o.correctsOperationId().toString(),
    o.targetAmount()==null?null:o.targetAmount().toPlainString(),o.correctionReason());
  }
 }
 public record OperationPageView(String accountId,String accountBalance,String currency,int page,
  int size,long totalElements,int totalPages,List<OperationReadView> items){}
}
