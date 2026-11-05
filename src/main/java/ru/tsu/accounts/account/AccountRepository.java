package ru.tsu.accounts.account;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<PersonalAccount, UUID> {
    Optional<PersonalAccount> findByAccountNumber(String number);
    Optional<PersonalAccount> findByIdAndClientId(UUID id, UUID clientId);
    boolean existsByClientIdAndStatusNot(UUID clientId, String status);
    Page<PersonalAccount> findByClientId(UUID clientId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PersonalAccount a where a.id = :id")
    Optional<PersonalAccount> lockById(@Param("id") UUID id);
    @Query("select a from PersonalAccount a where (:number = '' or a.accountNumber = :number) "
        + "and (:status = '' or a.status = :status) and (:clientId is null or a.clientId = :clientId)")
    Page<PersonalAccount> search(@Param("number") String number, @Param("status") String status,
                                 @Param("clientId") UUID clientId, Pageable pageable);
}
