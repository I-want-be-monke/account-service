package ru.tsu.accounts.identity;

import java.util.UUID;

public class UserCreateForm {
    private String login;
    private String fullName;
    private String password;
    private String roleCode;
    private UUID clientId;

    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
}
