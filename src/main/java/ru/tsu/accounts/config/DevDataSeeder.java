package ru.tsu.accounts.config;

import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.identity.AppRoleRepository;
import ru.tsu.accounts.identity.AppUser;
import ru.tsu.accounts.identity.AppUserRepository;

@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {
    private final AppRoleRepository roles;
    private final AppUserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Map<String, String> passwords;

    public DevDataSeeder(AppRoleRepository roles, AppUserRepository users, JdbcTemplate jdbc,
                         PasswordEncoder encoder,
                         @Value("${account.demo.passwords.admin}") String admin,
                         @Value("${account.demo.passwords.operator}") String operator,
                         @Value("${account.demo.passwords.accountant}") String accountant,
                         @Value("${account.demo.passwords.manager}") String manager,
                         @Value("${account.demo.passwords.client}") String client) {
        this.roles = roles;
        this.users = users;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.passwords = Map.of("ADMIN", admin, "OPERATOR", operator,
            "ACCOUNTANT", accountant, "MANAGER", manager, "CLIENT", client);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        passwords.values().forEach(p -> {
            if (p.length() < 12 || p.length() > 128) {
                throw new IllegalStateException("Each dev password must contain 12–128 characters");
            }
        });
        UUID clientId = jdbc.query("SELECT id FROM client WHERE identifier_key = 'DEMO-CLIENT-1'",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        if (clientId == null) {
            clientId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO client(id,client_type,display_name,identifier_type,identifier_value,identifier_key)
                VALUES(?,'PERSON','Учебный клиент','DEMO','DEMO-CLIENT-1','DEMO-CLIENT-1')
                """, clientId);
        }
        createIfMissing("demo-admin", "Учебный администратор", "ADMIN", null);
        createIfMissing("demo-operator", "Учебный оператор", "OPERATOR", null);
        createIfMissing("demo-accountant", "Учебный бухгалтер", "ACCOUNTANT", null);
        createIfMissing("demo-manager", "Учебный руководитель", "MANAGER", null);
        createIfMissing("demo-client", "Учебный клиент", "CLIENT", clientId);
    }

    private void createIfMissing(String login, String fullName, String roleCode, UUID clientId) {
        if (users.findByLogin(login).isPresent()) return;
        var role = roles.findById(roleCode).orElseThrow();
        users.save(new AppUser(login, fullName, encoder.encode(passwords.get(roleCode)), role, clientId));
    }
}
