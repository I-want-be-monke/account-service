package ru.tsu.accounts.operation;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface OperationRepository extends JpaRepository<FinancialOperation,UUID> {
 Optional<FinancialOperation> findByIdempotencyKey(UUID key);
 @Query("select o.accountId from FinancialOperation o where o.id=:id")
 Optional<UUID> findAccountIdById(@Param("id") UUID id);
 boolean existsByAccountIdAndStorageStatus(UUID id,String status);
 boolean existsByExternalSourceAndKindAndExternalNumberKeyAndStorageStatus(String source,String kind,String number,String status);
 Page<FinancialOperation> findByAccountIdAndStorageStatus(UUID id,String status,Pageable pageable);
 Page<FinancialOperation> findByAccountIdAndStorageStatusAndCreatedBy(UUID id,String status,UUID author,Pageable pageable);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select o from FinancialOperation o where o.id=:id")
 Optional<FinancialOperation> lockById(@Param("id") UUID id);
}
