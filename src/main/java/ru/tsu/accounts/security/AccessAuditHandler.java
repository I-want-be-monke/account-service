package ru.tsu.accounts.security;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.RequestIdFilter;

@Component
public class AccessAuditHandler implements AccessDeniedHandler {
    private static final Logger log = LoggerFactory.getLogger(AccessAuditHandler.class);
    private final AuditAttemptService attempts;

    public AccessAuditHandler(AuditAttemptService attempts) { this.attempts = attempts; }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
            : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        AccountPrincipal actor = principal instanceof AccountPrincipal p ? p : null;
        String category = request.getRequestURI().startsWith("/admin")
            || request.getRequestURI().startsWith("/audit") ? "SECURITY" : "ACCESS";
        try {
            attempts.record(AuditEntry.denied(actor == null ? null : actor.id(),
                actor == null ? null : actor.username(), category, "ACCESS_DENIED", null, null,
                "ROLE_OR_CSRF_DENIED", RequestIdFilter.id(request)));
        } catch (Exception failure) {
            log.error("Access denial audit unavailable; requestId={}", RequestIdFilter.id(request), failure);
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }
}
