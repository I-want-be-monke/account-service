package ru.tsu.accounts.operation;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.identity.UserCommandException;
@Component
public class OperationConfirmation {
 private static final String KEY=OperationConfirmation.class.getName()+".pending";
 public record Pending(UUID actor,UUID operation,String action,long version,String fingerprint,UUID idempotencyKey,
                       String token,Instant expiresAt) implements Serializable {
  @Serial private static final long serialVersionUID=1L;
 }
 public Pending prepare(HttpSession session,UUID actor,UUID operation,String action,long version,String fingerprint) {
  Pending p=new Pending(actor,operation,action,version,fingerprint,UUID.randomUUID(),
   UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
  session.setAttribute(KEY,p); return p;
 }
 public Pending view(HttpSession session,UUID actor,UUID operation) {
  Object state=session.getAttribute(KEY);
  if(!(state instanceof Pending p)||!p.actor().equals(actor)||!p.operation().equals(operation)
   ||!Instant.now().isBefore(p.expiresAt())) throw expired();
  return p;
 }
 public Pending check(HttpSession session,UUID actor,UUID operation,String action,String token) {
  Pending p=view(session,actor,operation);
  if(token==null||!p.token().equals(token)||!p.action().equals(action))throw expired();
  return p;
 }
 public void clear(HttpSession session){session.removeAttribute(KEY);}
 private static UserCommandException expired(){
  return new UserCommandException(HttpStatus.CONFLICT,"CONFIRMATION_EXPIRED","Подтверждение устарело. Проверьте черновик снова.");
 }
}
