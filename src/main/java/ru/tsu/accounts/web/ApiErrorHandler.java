package ru.tsu.accounts.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@RestControllerAdvice(assignableTypes=AccountReadApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiErrorHandler {
 private static final Logger log=LoggerFactory.getLogger(ApiErrorHandler.class);
 private final AuditAttemptService attempts;
 public ApiErrorHandler(AuditAttemptService attempts){this.attempts=attempts;}
 @ExceptionHandler(UserCommandException.class)
 public ResponseEntity<ProblemDetail> command(UserCommandException ex,HttpServletRequest request){
  if(ex.status()==HttpStatus.FORBIDDEN)denied(ex.reasonCode(),request);
  return problem(ex.status(),ex.reasonCode(),ex.getMessage(),request);
 }
 @ExceptionHandler(MethodArgumentTypeMismatchException.class)
 public ResponseEntity<ProblemDetail> argument(MethodArgumentTypeMismatchException ex,HttpServletRequest request){
  return problem(HttpStatus.BAD_REQUEST,"INVALID_PARAMETER","Некорректный параметр запроса.",request);
 }
 @ExceptionHandler(DataAccessException.class)
 public ResponseEntity<ProblemDetail> database(DataAccessException ex,HttpServletRequest request){
  return problem(HttpStatus.SERVICE_UNAVAILABLE,"DATABASE_UNAVAILABLE","Данные временно недоступны.",request);
 }
 private ResponseEntity<ProblemDetail> problem(HttpStatus status,String code,String detail,HttpServletRequest request){
  var body=ProblemDetail.forStatusAndDetail(status,detail);
  body.setTitle(status.getReasonPhrase());
  body.setProperty("code",code);
  body.setProperty("requestId",RequestIdFilter.id(request).toString());
  return ResponseEntity.status(status).body(body);
 }
 private void denied(String reason,HttpServletRequest request){
  var auth=SecurityContextHolder.getContext().getAuthentication();
  AccountPrincipal actor=auth!=null&&auth.getPrincipal() instanceof AccountPrincipal p?p:null;
  try{
   attempts.record(AuditEntry.denied(actor==null?null:actor.id(),
    actor==null?null:actor.username(),"SECURITY","USER_COMMAND_DENIED",
    null,null,reason,RequestIdFilter.id(request)));
  }catch(Exception failure){
   log.error("API denial audit unavailable; requestId={}",RequestIdFilter.id(request),failure);
  }
 }
}
