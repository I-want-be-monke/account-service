package ru.tsu.accounts.account;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account_status_history")
public class AccountStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "account_id", nullable = false, updatable = false) private UUID accountId;
    @Column(name = "old_status", updatable = false, length = 16) private String oldStatus;
    @Column(name = "new_status", nullable = false, updatable = false, length = 16) private String newStatus;
    @Column(name = "changed_at", insertable = false, updatable = false) private Instant changedAt;
    @Column(name = "changed_by", updatable = false) private UUID changedBy;
    @Column(nullable = false, updatable = false, length = 500) private String reason;
    @Column(name = "request_id", nullable = false, updatable = false) private UUID requestId;
    protected AccountStatusHistory() { }
    public AccountStatusHistory(UUID accountId, String oldStatus, String newStatus,
                                UUID changedBy, String reason, UUID requestId) {
        this.accountId = accountId; this.oldStatus = oldStatus; this.newStatus = newStatus;
        this.changedBy = changedBy; this.reason = reason; this.requestId = requestId;
    }
    public Long getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public String getOldStatus() { return oldStatus; }
    public String getNewStatus() { return newStatus; }
    public Instant getChangedAt() { return changedAt; }
    public UUID getChangedBy() { return changedBy; }
    public String getReason() { return reason; }
    public UUID getRequestId() { return requestId; }
}
