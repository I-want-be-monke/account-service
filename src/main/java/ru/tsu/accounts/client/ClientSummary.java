package ru.tsu.accounts.client;

import java.time.Instant;
import java.util.UUID;

public record ClientSummary(UUID id, String clientType, String displayName, String identifierType,
                            String identifierValue, String phone, String email, String status,
                            long version, Instant createdAt, Instant updatedAt) {
    public static ClientSummary from(Client c) {
        return new ClientSummary(c.getId(), c.getClientType(), c.getDisplayName(),
            c.getIdentifierType(), c.getIdentifierValue(), c.getPhone(), c.getEmail(),
            c.getStatus(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
