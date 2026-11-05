package ru.tsu.accounts.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditWriter writer;
    public AuditService(AuditWriter writer) { this.writer = writer; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCriticalSuccess(AuditEntry entry) { writer.insert(entry); }
}
