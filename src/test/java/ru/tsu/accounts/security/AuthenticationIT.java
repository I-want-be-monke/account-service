package ru.tsu.accounts.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"server.ssl.enabled=false", "account.http.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class AuthenticationIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm")
        .withDatabaseName("accounts")
        .withUsername("account_owner")
        .withPassword("test-owner-password")
        .withInitScript("db/test-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("account.demo.passwords.admin", () -> "correct-password-for-e1");
        registry.add("account.demo.passwords.operator", () -> "operator-password-for-e1");
        registry.add("account.demo.passwords.accountant", () -> "accountant-password-for-e1");
        registry.add("account.demo.passwords.manager", () -> "manager-password-for-e1");
        registry.add("account.demo.passwords.client", () -> "client-password-for-e1");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void migratesAndKeepsRolesAndUsersOnRepeatedMigration() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(5);
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE login='demo-admin'", String.class);
        assertThat(hash).startsWith("$2a$12$").doesNotContain("correct-password-for-e1");
    }

    @Test
    void loginCsrfSessionAndLogout() throws Exception {
        mvc.perform(get("/login").secure(true)).andExpect(status().isOk());
        mvc.perform(get("/").secure(true)).andExpect(status().is3xxRedirection())
            .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).contains("/login"));
        mvc.perform(post("/login").secure(true).param("username", "demo-admin")
                .param("password", "correct-password-for-e1"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/login").secure(true).with(csrf()).param("username", "demo-admin")
                .param("password", "wrong-password"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?error"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='LOGIN' AND result='DENIED' AND actor_login_snapshot='demo-admin'", Integer.class)).isPositive();

        var result = mvc.perform(post("/login").secure(true).with(csrf())
                .param("username", "demo-admin").param("password", "correct-password-for-e1"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"))
            .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("ACCOUNT_SESSION=").contains("HttpOnly").contains("Secure");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='LOGIN' AND result='SUCCESS' AND actor_login_snapshot='demo-admin'", Integer.class)).isPositive();
        String value = setCookie.split("ACCOUNT_SESSION=", 2)[1].split(";", 2)[0];
        Cookie cookie = new Cookie("ACCOUNT_SESSION", value);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isPositive();
        mvc.perform(get("/").secure(true).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(post("/logout").secure(true).cookie(cookie).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?logout"));
        mvc.perform(get("/").secure(true).cookie(cookie)).andExpect(status().is3xxRedirection());
    }
}
