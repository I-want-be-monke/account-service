package ru.tsu.accounts.client;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClientRepository extends JpaRepository<Client, UUID> {
    Optional<Client> findByClientTypeAndIdentifierTypeAndIdentifierKey(
        String clientType, String identifierType, String identifierKey);

    @Query("select c from Client c where (:name = '' or lower(c.displayName) like concat(:name, '%')) "
        + "and (:type = '' or c.clientType = :type) "
        + "and (:key = '' or c.identifierKey = :key) "
        + "and (:status = '' or c.status = :status)")
    Page<Client> search(@Param("name") String name, @Param("type") String type,
                        @Param("key") String key, @Param("status") String status, Pageable pageable);
}
