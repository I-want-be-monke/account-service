package ru.tsu.accounts.client;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "client")
public class Client {
    @Id private UUID id;
    @Column(name = "client_type", nullable = false, length = 16) private String clientType;
    @Column(name = "display_name", nullable = false, length = 200) private String displayName;
    @Column(name = "identifier_type", nullable = false, length = 32) private String identifierType;
    @Column(name = "identifier_value", nullable = false, length = 100) private String identifierValue;
    @Column(name = "identifier_key", nullable = false, length = 100) private String identifierKey;
    @Column(length = 32) private String phone;
    @Column(length = 254) private String email;
    @Column(nullable = false, length = 16) private String status;
    @Version private Long version;
    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", insertable = false) private Instant updatedAt;

    protected Client() { }
    public Client(String type, String name, String identifierType, String value, String key,
                  String phone, String email) {
        id = UUID.randomUUID();
        clientType = type; displayName = name; this.identifierType = identifierType;
        identifierValue = value; identifierKey = key; this.phone = phone; this.email = email;
        status = "ACTIVE";
    }
    public void update(String type, String name, String identifierType, String value, String key,
                       String phone, String email) {
        clientType = type; displayName = name; this.identifierType = identifierType;
        identifierValue = value; identifierKey = key; this.phone = phone; this.email = email;
        updatedAt = Instant.now();
    }
    public void changeStatus(String value) { status = value; updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getClientType() { return clientType; }
    public String getDisplayName() { return displayName; }
    public String getIdentifierType() { return identifierType; }
    public String getIdentifierValue() { return identifierValue; }
    public String getIdentifierKey() { return identifierKey; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getStatus() { return status; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
