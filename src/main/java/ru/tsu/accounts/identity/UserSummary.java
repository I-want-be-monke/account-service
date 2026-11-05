package ru.tsu.accounts.identity;

import java.util.UUID;

public record UserSummary(UUID id, String login, String fullName, String roleCode, String roleName,
                          String status, UUID clientId, long authVersion, Long version) {
    public static UserSummary from(AppUser user) {
        return new UserSummary(user.getId(), user.getLogin(), user.getFullName(),
            user.getRole().getCode(), user.getRole().getDisplayName(), user.getStatus(), user.getClientId(),
            user.getAuthVersion(), user.getVersion());
    }
}
