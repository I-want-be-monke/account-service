package ru.tsu.accounts.security;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.tsu.accounts.identity.AppUserRepository;

public class CurrentUserFilter extends OncePerRequestFilter {
    private final AppUserRepository users;

    public CurrentUserFilter(AppUserRepository users) { this.users = users; }

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
