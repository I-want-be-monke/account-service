package ru.tsu.accounts;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;

@SpringBootApplication
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AccountServiceApplication.class);
        app.addInitializers(context -> {
            if (!context.getEnvironment().matchesProfiles("migration")) return;
            if (context instanceof WebServerApplicationContext)
                throw new IllegalStateException("The migration profile requires a non-web application");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("migrationSafety", Map.of(
                "spring.flyway.enabled", false, "spring.sql.init.mode", "never",
                "spring.jpa.hibernate.ddl-auto", "validate")));
        });
        var context = app.run(args);
        if (context.getEnvironment().matchesProfiles("migration"))
            System.exit(SpringApplication.exit(context));
    }
}
