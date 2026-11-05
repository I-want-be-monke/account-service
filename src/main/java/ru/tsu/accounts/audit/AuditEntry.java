package ru.tsu.accounts.audit;

import java.util.Map;
import java.util.UUID;

public record AuditEntry(UUID actorId, String actorLogin, String actorType, String category, String action,
                         String objectType, String objectId, String result, String reasonCode,
                         UUID requestId, Map<String, ?> before, Map<String, ?> after,
                         Map<String, ?> details) {
    public static AuditEntry user(UUID actor, String login, String action, UUID object,
                                  Map<String, ?> before, Map<String, ?> after, UUID requestId) {
        return new AuditEntry(actor, login, "USER", "SECURITY", action, "USER", object.toString(),
            "SUCCESS", null, requestId, before, after, null);
    }

    public static AuditEntry userWithReason(UUID actor, String login, String action, UUID object,
                                            Map<String, ?> before, Map<String, ?> after,
                                            String reason, UUID requestId) {
        return new AuditEntry(actor, login, "USER", "SECURITY", action, "USER", object.toString(),
            "SUCCESS", null, requestId, before, after, Map.of("reason", reason));
    }

    public static AuditEntry systemUserCreated(UUID object, String login) {
        return new AuditEntry(null, "system", "SYSTEM", "SECURITY", "USER_CREATED", "USER",
            object.toString(), "SUCCESS", null, UUID.randomUUID(), null,
            Map.of("login", login, "role", "ADMIN", "status", "ACTIVE"), null);
    }

    public static AuditEntry denied(UUID actor, String login, String category, String action,
                                    String objectType, String objectId, String reason, UUID requestId) {
        return new AuditEntry(actor, login, actor == null ? "ANONYMOUS" : "USER", category, action, objectType, objectId,
            "DENIED", reason, requestId, null, null, null);
    }
}
