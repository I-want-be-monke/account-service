package ru.tsu.accounts.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import ru.tsu.accounts.identity.AppUserRepository;
import ru.tsu.accounts.audit.AuditAttemptService;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppUserRepository users,
                                            AuditAttemptService attempts,
                                            AuthenticationAuditHandlers authenticationHandlers,
                                            AccessAuditHandler accessHandler,
                                            UserDetailsService userDetailsService,
                                            @Value("${account.http.port:8080}") int httpPort,
                                            @Value("${server.port:8443}") int httpsPort) throws Exception {
        if (httpPort > 0 && httpsPort > 0 && (httpPort != 8080 || httpsPort != 8443)) {
            http.portMapper(ports -> ports.http(httpPort).mapsTo(httpsPort));
        }
        http.userDetailsService(userDetailsService)
            .requiresChannel(channel -> channel.anyRequest().requiresSecure())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/vendor/**", "/icons/**", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/ui/clients/search").hasAnyRole("ADMIN", "OPERATOR", "ACCOUNTANT", "MANAGER")
                .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/audit/**", "/audit").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers("/reports", "/reports/**", "/reports.csv", "/api/v1/reports", "/api/v1/reports/**")
                    .hasAnyRole("ADMIN", "ACCOUNTANT", "MANAGER")
                .requestMatchers("/clients/**", "/clients").hasAnyRole("ADMIN", "OPERATOR", "ACCOUNTANT", "MANAGER")
                .requestMatchers("/me/client", "/my/accounts", "/my/accounts/**").hasRole("CLIENT")
                .requestMatchers("/operations/**").hasAnyRole("OPERATOR", "ACCOUNTANT", "MANAGER", "CLIENT")
                .requestMatchers("/accounts", "/accounts/**").hasAnyRole("ADMIN", "OPERATOR", "ACCOUNTANT", "MANAGER", "CLIENT")
                .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                .successHandler(authenticationHandlers).failureHandler(authenticationHandlers).permitAll())
            .exceptionHandling(ex -> ex.accessDeniedHandler(accessHandler)
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                    new NegatedRequestMatcher(new AntPathRequestMatcher("/api/v1/**")))
                .defaultAuthenticationEntryPointFor(
                    (request,response,error)->accessHandler.unauthenticated(request,response),
                    new AntPathRequestMatcher("/api/v1/**")))
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true).deleteCookies("ACCOUNT_SESSION"))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; img-src 'self' data:; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")))
            .addFilterAfter(new CurrentUserFilter(users, attempts, accessHandler), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
