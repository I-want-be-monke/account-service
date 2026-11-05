package ru.tsu.accounts.account;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.identity.AppUserRepository;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@Component
public class AccountAccessPolicy {
 private final AccountRepository accounts;
 private final AppUserRepository users;
 public AccountAccessPolicy(AccountRepository accounts,AppUserRepository users){
  this.accounts=accounts;this.users=users;
 }
 @PreAuthorize("isAuthenticated()")
 public PersonalAccount require(UUID id){
  AccountPrincipal actor=principal();
  if("CLIENT".equals(actor.role()))
   return accounts.findByIdAndClientId(id,ownClientId())
    .orElseThrow(AccountAccessPolicy::forbidden);
  if(!Set.of("ADMIN","OPERATOR","ACCOUNTANT","MANAGER").contains(actor.role()))throw forbidden();
  return accounts.findById(id).orElseThrow(()->new UserCommandException(
   HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Счет не найден."));
 }
 @PreAuthorize("hasRole('CLIENT')")
 public UUID ownClientId(){
  UUID id=users.findById(principal().id()).orElseThrow(AccountAccessPolicy::forbidden).getClientId();
  if(id==null)throw forbidden();
  return id;
 }
 private AccountPrincipal principal(){
  return (AccountPrincipal)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
 }
 private static UserCommandException forbidden(){
  return new UserCommandException(HttpStatus.FORBIDDEN,"ACCOUNT_NOT_OWNED","Счет недоступен.");
 }
}
