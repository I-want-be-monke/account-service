package ru.tsu.accounts.security;

import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.identity.AppUser;
import ru.tsu.accounts.identity.AppUserRepository;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final AppUserRepository users;

    public DatabaseUserDetailsService(AppUserRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String rawLogin) {
        String login = rawLogin == null ? "" : rawLogin.strip().toLowerCase(Locale.ROOT);
        AppUser user = users.findByLogin(login)
            .filter(u -> "ACTIVE".equals(u.getStatus()))
            .orElseThrow(() -> new UsernameNotFoundException("Неверный логин или пароль"));
        return new AccountPrincipal(user.getId(), user.getLogin(), user.getPasswordHash(),
            user.getFullName(), user.getRole().getCode(), user.getAuthVersion());
    }
}
