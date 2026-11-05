package ru.tsu.accounts.client;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.identity.UserCommandException;

@Component
public class ClientStatusConfirmation {
    private static final String KEY = ClientStatusConfirmation.class.getName() + ".pending";
    public record Pending(UUID actorId, UUID clientId, long version, String status, String reason,
                          String token, Instant expiresAt) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }
    public void prepare(HttpSession session, UUID actorId, UUID clientId, long version,
                        String status, String reason) {
        session.setAttribute(KEY, new Pending(actorId, clientId, version, status, reason.strip(),
            UUID.randomUUID().toString(), Instant.now().plusSeconds(600)));
    }
    public Pending view(HttpSession session, UUID actorId, UUID clientId) {
        Object value = session.getAttribute(KEY);
        if (!(value instanceof Pending p) || !p.actorId().equals(actorId)
            || !p.clientId().equals(clientId) || !Instant.now().isBefore(p.expiresAt()))
            throw expired();
        return p;
    }
    public Pending consume(HttpSession session, UUID actorId, UUID clientId, String token) {
        Pending p = view(session, actorId, clientId);
        if (token == null || !p.token().equals(token)) throw expired();
        session.removeAttribute(KEY);
        return p;
    }
    private static UserCommandException expired() {
        return new UserCommandException(HttpStatus.CONFLICT, "CONFIRMATION_EXPIRED",
            "Подтверждение устарело. Повторите выбор изменения.");
    }
}
