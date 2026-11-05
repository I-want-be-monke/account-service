package ru.tsu.accounts.client;

public class ClientForm {
    private String clientType;
    private String displayName;
    private String identifierType;
    private String identifierValue;
    private String phone;
    private String email;
    private long version;
    public static ClientForm from(ClientSummary c) {
        ClientForm f = new ClientForm();
        f.clientType = c.clientType(); f.displayName = c.displayName();
        f.identifierType = c.identifierType(); f.identifierValue = c.identifierValue();
        f.phone = c.phone(); f.email = c.email(); f.version = c.version();
        return f;
    }
    public String getClientType() { return clientType; }
    public void setClientType(String value) { clientType = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    public String getIdentifierType() { return identifierType; }
    public void setIdentifierType(String value) { identifierType = value; }
    public String getIdentifierValue() { return identifierValue; }
    public void setIdentifierValue(String value) { identifierValue = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public long getVersion() { return version; }
    public void setVersion(long value) { version = value; }
}
