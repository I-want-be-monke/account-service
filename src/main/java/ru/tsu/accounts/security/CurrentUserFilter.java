package ru.tsu.accounts.security;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.tsu.accounts.identity.AppUserRepository;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.RequestIdFilter;

public class CurrentUserFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(CurrentUserFilter.class);
    private final AppUserRepository users;
    private final AuditAttemptService attempts;

    public CurrentUserFilter(AppUserRepository users, AuditAttemptService attempts) {
        this.users = users; this.attempts = attempts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccountPrincipal principal) {
            boolean current = users.findById(principal.id())
                .map(u -> "ACTIVE".equals(u.getStatus())
                    && u.getAuthVersion() == principal.authVersion()
                    && u.getRole().getCode().equals(principal.role()))
                .orElse(false);
            if (!current) {
                try {
                    attempts.record(AuditEntry.denied(principal.id(), principal.username(), "SECURITY",
                        "SESSION_REVOKED", "USER", principal.id().toString(), "AUTH_VERSION_OR_STATUS",
                        RequestIdFilter.id(request)));
                } catch (Exception failure) {
                    log.error("Session revocation audit unavailable; requestId={}", RequestIdFilter.id(request), failure);
                }
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                response.sendRedirect("/login?expired");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
