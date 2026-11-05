package ru.tsu.accounts.identity;

import java.util.UUID;

public class AccessChangeForm {
    private String roleCode;
    private String status;
    private UUID clientId;
    private String reason;
    private long version;

    public static AccessChangeForm from(UserSummary user) {
        AccessChangeForm form = new AccessChangeForm();
        form.roleCode = user.roleCode(); form.status = user.status();
        form.clientId = user.clientId(); form.version = user.version();
        return form;
    }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
