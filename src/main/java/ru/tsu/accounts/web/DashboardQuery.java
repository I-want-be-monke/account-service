package ru.tsu.accounts.web;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.account.AccountService;
import ru.tsu.accounts.operation.OperationRepository;
import ru.tsu.accounts.operation.OperationSummary;
import ru.tsu.accounts.security.AccountPrincipal;

/** A bounded, author-scoped view of existing drafts, never a new posting path. */
@Service
public class DashboardQuery {
    private final OperationRepository operations;
    private final AccountService accounts;
    public DashboardQuery(OperationRepository operations, AccountService accounts) {
        this.operations = operations; this.accounts = accounts;
    }
    @PreAuthorize("hasRole('ACCOUNTANT')")
    @Transactional(readOnly = true)
    public List<Draft> ownDrafts() {
        UUID author = ((AccountPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).id();
        return operations.findTop5ByStorageStatusAndCreatedByOrderByCreatedAtDescIdDesc("PREPARED", author).stream()
            .map(operation -> new Draft(OperationSummary.from(operation), accounts.get(operation.getAccountId()).accountNumber()))
            .toList();
    }
    public record Draft(OperationSummary operation, String accountNumber) { }
}
