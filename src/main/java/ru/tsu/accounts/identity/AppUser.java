package ru.tsu.accounts.identity;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String login;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_code", nullable = false)
    private AppRole role;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @Version
    private Long version;

    protected AppUser() { }

    public AppUser(String login, String fullName, String passwordHash, AppRole role, UUID clientId) {
        this.id = UUID.randomUUID();
        this.login = login;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.clientId = clientId;
        this.status = "ACTIVE";
    }

    public UUID getId() { return id; }
    public String getLogin() { return login; }
    public String getFullName() { return fullName; }
    public String getPasswordHash() { return passwordHash; }
    public AppRole getRole() { return role; }
    public UUID getClientId() { return clientId; }
    public String getStatus() { return status; }
    public long getAuthVersion() { return authVersion; }
}
