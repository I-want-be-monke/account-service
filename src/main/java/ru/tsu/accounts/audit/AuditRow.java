package ru.tsu.accounts.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditRow(long id, Instant occurredAt, UUID actorId, String actorLogin,
                       String category, String action, String objectType, String objectId,
                       String result, String reasonCode, UUID requestId,
                       String beforeData, String afterData, String details) { }
