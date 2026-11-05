package ru.tsu.accounts.audit;

import java.time.LocalDate;
import java.util.UUID;

public record AuditSearch(LocalDate from, LocalDate to, UUID actorId, String objectType,
                          String objectId, String action, String result, String category, int page) { }
