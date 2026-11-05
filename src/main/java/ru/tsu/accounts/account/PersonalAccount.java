package ru.tsu.accounts.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "personal_account")
public class PersonalAccount {
    @Id private UUID id;
    @Column(name = "account_number", nullable = false, updatable = false, length = 32)
    private String accountNumber;
    @Column(name = "client_id", nullable = false, updatable = false) private UUID clientId;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "opened_on", nullable = false, updatable = false) private LocalDate openedOn;
    @Column(name = "closed_on") private LocalDate closedOn;
    @Column(name = "history_start_on", nullable = false, updatable = false) private LocalDate historyStartOn;
    @Column(name = "opening_balance", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance;
    @Column(name = "current_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal currentBalance;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Version private Long version;
    @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", insertable = false) private Instant updatedAt;

    protected PersonalAccount() { }
    public PersonalAccount(UUID id, String number, UUID clientId, LocalDate openedOn) {
        this.id = id; this.accountNumber = number; this.clientId = clientId;
        this.status = "OPEN"; this.openedOn = openedOn; this.historyStartOn = openedOn;
        this.openingBalance = new BigDecimal("0.00"); this.currentBalance = new BigDecimal("0.00");
        this.currency = "RUB";
    }
    public void changeStatus(String status, LocalDate date) {
        this.status = status;
        this.closedOn = status.equals("CLOSED") ? date : null;
        this.updatedAt = Instant.now();
    }
    public void applyPostedBalance(BigDecimal nextBalance) {
        this.currentBalance = nextBalance;
        this.updatedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public UUID getClientId() { return clientId; }
    public String getStatus() { return status; }
    public LocalDate getOpenedOn() { return openedOn; }
    public LocalDate getClosedOn() { return closedOn; }
    public LocalDate getHistoryStartOn() { return historyStartOn; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public BigDecimal getCurrentBalance() { return currentBalance; }
    public String getCurrency() { return currency; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
