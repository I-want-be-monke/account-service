package ru.tsu.accounts.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditService;
import ru.tsu.accounts.identity.AppRoleRepository;
import ru.tsu.accounts.identity.AppUser;
import ru.tsu.accounts.identity.AppUserRepository;

@Component
@Profile("!dev")
public class BootstrapAdmin implements ApplicationRunner {
    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final String password;

    public BootstrapAdmin(AppUserRepository users, AppRoleRepository roles, PasswordEncoder encoder,
                          AuditService audit, @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
        this.users = users; this.roles = roles; this.encoder = encoder; this.audit = audit; this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password.isEmpty() || users.count() != 0) return;
        if (password.length() < 12 || password.length() > 128)
            throw new IllegalStateException("Bootstrap password must contain 12–128 characters");
        var role = roles.findById("ADMIN").orElseThrow();
        var admin = users.saveAndFlush(new AppUser("bootstrap-admin", "Первый администратор",
            encoder.encode(password), role, null));
        audit.recordCriticalSuccess(AuditEntry.systemUserCreated(admin.getId(), admin.getLogin()));
    }
}
