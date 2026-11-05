package ru.tsu.accounts.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByLogin(String login);
    @Query("select u from AppUser u where (:login = '' or u.login like concat('%', :login, '%')) "
        + "and (:role = '' or u.role.code = :role) and (:status = '' or u.status = :status)")
    Page<AppUser> search(@Param("login") String login, @Param("role") String role,
                         @Param("status") String status, Pageable pageable);
}
