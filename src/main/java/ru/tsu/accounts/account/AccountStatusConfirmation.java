package ru.tsu.accounts.account;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.identity.UserCommandException;

@Component
public class AccountStatusConfirmation {
    private static final String KEY = AccountStatusConfirmation.class.getName() + ".pending";
    public record Pending(UUID actorId, UUID accountId, long version, String status,
                          String reason, String token, Instant expiresAt) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }
    public void prepare(HttpSession session, UUID actor, UUID account, long version,
                        String status, String reason) {
        session.setAttribute(KEY, new Pending(actor, account, version, status, reason.strip(),
            UUID.randomUUID().toString(), Instant.now().plusSeconds(600)));
    }
    public Pending view(HttpSession session, UUID actor, UUID account) {
        Object pending = session.getAttribute(KEY);
        if (!(pending instanceof Pending p) || !p.actorId().equals(actor)
            || !p.accountId().equals(account) || !Instant.now().isBefore(p.expiresAt()))
            throw expired();
        return p;
    }
    public Pending consume(HttpSession session, UUID actor, UUID account, String token) {
        Pending p = view(session, actor, account);
        if (token == null || !p.token().equals(token)) throw expired();
        session.removeAttribute(KEY);
        return p;
    }
    private static UserCommandException expired() {
        return new UserCommandException(HttpStatus.CONFLICT, "CONFIRMATION_EXPIRED",
            "Подтверждение устарело. Повторите выбор изменения.");
    }
}
