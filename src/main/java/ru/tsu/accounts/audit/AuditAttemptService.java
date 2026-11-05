package ru.tsu.accounts.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditAttemptService {
    private final AuditWriter writer;
    public AuditAttemptService(AuditWriter writer) { this.writer = writer; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEntry entry) { writer.insert(entry); }
}
