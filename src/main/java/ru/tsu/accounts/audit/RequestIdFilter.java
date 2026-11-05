package ru.tsu.accounts.audit;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    public static UUID id(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof UUID id ? id : UUID.randomUUID();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        UUID id = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Request-ID", id.toString());
        try {
            chain.doFilter(request, response);
        } catch (DataAccessResourceFailureException | CannotCreateTransactionException failure) {
            // JDBC session access can fail before the MVC exception handlers run.
            if (response.isCommitted()) throw failure;
            log.error("Database unavailable outside MVC; requestId={}", id, failure);
            response.reset();
            response.setHeader("X-Request-ID", id.toString());
            response.setHeader("Cache-Control", "no-store");
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            if (request.getServletPath().startsWith("/api/v1/")) {
                response.setContentType("application/problem+json;charset=UTF-8");
                response.getWriter().printf("""
                    {"type":"about:blank","title":"Service Unavailable","status":503,
                    "code":"DATABASE_UNAVAILABLE","detail":"Данные временно недоступны.","requestId":"%s"}
                    """, id);
            } else {
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().printf("Сервис временно недоступен. Перед повтором проверьте состояние операции.%nID запроса: %s%n", id);
            }
        }
    }
}
