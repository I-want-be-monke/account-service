package ru.tsu.accounts.security;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.identity.AccessChangeForm;
import ru.tsu.accounts.identity.UserCommandException;

@Component
public class ConfirmationGuard {
    private static final String KEY = ConfirmationGuard.class.getName() + ".pending";

    public record Pending(UUID actorId, UUID userId, long version, String roleCode, String status,
                          UUID clientId, String reason, String token, Instant expiresAt) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }

    public Pending prepare(HttpSession session, UUID actorId, UUID userId, AccessChangeForm form) {
        Pending pending = new Pending(actorId, userId, form.getVersion(), form.getRoleCode(),
            form.getStatus(), form.getClientId(), form.getReason().strip(),
            UUID.randomUUID().toString(), Instant.now().plusSeconds(600));
        session.setAttribute(KEY, pending);
        return pending;
    }

    public Pending view(HttpSession session, UUID actorId, UUID userId) {
        Object value = session.getAttribute(KEY);
        if (!(value instanceof Pending pending) || !pending.actorId().equals(actorId)
            || !pending.userId().equals(userId) || !Instant.now().isBefore(pending.expiresAt()))
            throw missing();
        return pending;
    }

    public Pending consume(HttpSession session, UUID actorId, UUID userId, String token) {
        Pending pending = view(session, actorId, userId);
        if (token == null || !pending.token().equals(token)) throw missing();
        session.removeAttribute(KEY);
        return pending;
    }

    private UserCommandException missing() {
        return new UserCommandException(HttpStatus.CONFLICT, "CONFIRMATION_EXPIRED",
            "Подтверждение устарело. Повторите выбор изменения.");
    }
}
