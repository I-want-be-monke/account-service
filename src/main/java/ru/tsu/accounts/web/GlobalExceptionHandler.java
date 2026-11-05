package ru.tsu.accounts.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import ru.tsu.accounts.audit.RequestIdFilter;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.identity.UserCommandException;
import ru.tsu.accounts.security.AccountPrincipal;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final AuditAttemptService attempts;

    public GlobalExceptionHandler(AuditAttemptService attempts) { this.attempts = attempts; }

    @ExceptionHandler(UserCommandException.class)
    public ModelAndView userCommand(UserCommandException ex, HttpServletRequest request) {
        if (ex.status() == HttpStatus.FORBIDDEN) denied(request, ex.reasonCode());
        return page(ex.status(), ex.getMessage(), request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    public ModelAndView conflict(Exception ex, HttpServletRequest request) {
        log.warn("Data conflict; requestId={}", RequestIdFilter.id(request), ex);
        if (request.getMethod().equals("POST") && (request.getRequestURI().startsWith("/admin/users")
            || request.getRequestURI().startsWith("/clients/")
            || request.getRequestURI().startsWith("/accounts/")
            || request.getRequestURI().startsWith("/operations/")))
            denied(request, "DATA_CONFLICT");
        return page(HttpStatus.CONFLICT, "Запись уже существует или была изменена. Обновите страницу.", request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ModelAndView unavailable(DataAccessException ex, HttpServletRequest request) {
        log.error("Database unavailable; requestId={}", RequestIdFilter.id(request), ex);
        return page(HttpStatus.SERVICE_UNAVAILABLE, "Операция не выполнена. Повторите позже.", request);
    }

    private ModelAndView page(HttpStatus status, String message, HttpServletRequest request) {
        ModelAndView result = new ModelAndView("error");
        result.setStatus(status);
        result.addObject("status", status.value());
        result.addObject("message", message);
        result.addObject("requestId", RequestIdFilter.id(request));
        return result;
    }

    private void denied(HttpServletRequest request, String reason) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        AccountPrincipal actor = auth != null && auth.getPrincipal() instanceof AccountPrincipal p ? p : null;
        try {
            attempts.record(AuditEntry.denied(actor == null ? null : actor.id(),
                actor == null ? null : actor.username(), "SECURITY", "USER_COMMAND_DENIED",
                null, null, reason, RequestIdFilter.id(request)));
        } catch (Exception failure) {
            log.error("Denial audit unavailable; requestId={}", RequestIdFilter.id(request), failure);
        }
    }
}
