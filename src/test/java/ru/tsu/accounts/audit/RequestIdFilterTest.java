package ru.tsu.accounts.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/accounts/id", "/operations/id/confirm"})
    void jdbcSessionFailureReturnsSafeCorrelated503(String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        var response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request, response, (req, res) -> {
            response.setHeader("Location", "/operations/id?posted");
            throw new CannotGetJdbcConnectionException("jdbc:postgresql://private-db/test");
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("X-Request-ID")).isEqualTo(RequestIdFilter.id(request).toString());
        assertThat(response.getContentAsString()).contains(RequestIdFilter.id(request).toString())
            .doesNotContain("private-db", "?posted");
        assertThat(response.getContentType()).startsWith(path.startsWith("/api/")
            ? "application/problem+json" : "text/plain");
    }
}
