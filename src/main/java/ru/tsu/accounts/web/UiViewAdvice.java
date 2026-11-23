package ru.tsu.accounts.web;

import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.tsu.accounts.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;

@ControllerAdvice
public class UiViewAdvice {
    @ModelAttribute
    public void currentUser(Authentication authentication, Model model, HttpServletRequest request) {
        // The shared sidebar can exceed the response buffer before the logout form is rendered.
        // Resolve the deferred CSRF token while a new session cookie can still be written.
        if (!request.getRequestURI().startsWith("/api/") && !request.getRequestURI().startsWith("/ui/")) {
            Object token = request.getAttribute(CsrfToken.class.getName());
            if (token instanceof CsrfToken csrf) csrf.getToken();
        }
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            model.addAttribute("uiUser", principal);
            model.addAttribute("uiRole", principal.role());
        }
    }
}
