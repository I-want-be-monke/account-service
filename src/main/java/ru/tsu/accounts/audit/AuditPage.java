package ru.tsu.accounts.audit;

import java.util.List;

public record AuditPage(List<AuditRow> rows, long total, int page, int size) {
    public boolean hasNext() { return (long)(page + 1) * size < total; }
}
