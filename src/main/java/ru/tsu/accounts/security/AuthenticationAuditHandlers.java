package ru.tsu.accounts.security;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.identity.AppUserRepository;

@Component
public class AuthenticationAuditHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationAuditHandlers.class);
    private final AuditAttemptService attempts;
    private final AppUserRepository users;

    public AuthenticationAuditHandlers(AuditAttemptService attempts, AppUserRepository users) {
        this.attempts = attempts; this.users = users;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        AccountPrincipal user = (AccountPrincipal) authentication.getPrincipal();
        try {
            attempts.record(AuditEntry.user(user.id(), user.username(), "LOGIN", user.id(),
                null, null, RequestIdFilter.id(request)));
        } catch (Exception failure) {
            SecurityContextHolder.clearContext();
            request.getSession().invalidate();
            log.error("Login audit failed; requestId={}", RequestIdFilter.id(request), failure);
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Вход временно недоступен.");
            return;
        }
        response.sendRedirect(request.getContextPath() + "/");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String attemptedLogin = request.getParameter("username");
        try {
            String normalized = attemptedLogin == null ? "" : attemptedLogin.strip().toLowerCase(Locale.ROOT);
            UUID knownId = normalized.length() <= 100 ? users.findByLogin(normalized)
                .map(u -> u.getId()).orElse(null) : null;
            attempts.record(AuditEntry.denied(knownId, attemptedLogin, "SECURITY", "LOGIN",
                "USER", null, "INVALID_CREDENTIALS", RequestIdFilter.id(request)));
        } catch (Exception failure) {
            log.error("Failed login audit unavailable; requestId={}", RequestIdFilter.id(request), failure);
        }
        response.sendRedirect(request.getContextPath() + "/login?error");
    }
}
