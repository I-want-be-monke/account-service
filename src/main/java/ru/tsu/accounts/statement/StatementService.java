package ru.tsu.accounts.statement;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.account.AccountAccessPolicy;
import ru.tsu.accounts.account.PersonalAccount;
import ru.tsu.accounts.identity.UserCommandException;

@Service
public class StatementService {
    private static final String ROLES = "hasAnyRole('ADMIN','OPERATOR','ACCOUNTANT','MANAGER','CLIENT')";
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String ROWS = """
        select o.id,o.kind,
          case when exists (
            select 1 from financial_operation c
            where c.corrects_operation_id=o.id and c.storage_status='POSTED'
              and c.operation_date<=?) then 'CORRECTED' else 'POSTED' end as display_status,
          o.operation_date,o.posted_at,o.amount,o.delta,o.external_number,o.basis,
          o.period_start,o.payment_method,o.corrects_operation_id,o.target_amount,o.correction_reason
        from financial_operation o
        where o.account_id=? and o.storage_status='POSTED'
          and o.operation_date>=? and o.operation_date<=?
        order by o.operation_date asc,o.posted_at asc,o.id asc
        """;

    private final JdbcTemplate jdbc;
    private final AccountAccessPolicy access;

    public StatementService(JdbcTemplate jdbc, AccountAccessPolicy access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** Starts its own short snapshot even when called from another transaction. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
        propagation = Propagation.REQUIRES_NEW)
    @PreAuthorize(ROLES)
    public Statement generate(UUID accountId, String from, String to) {
        List<StatementLine> lines = new ArrayList<>();
        Statement metadata = read(accountId, from, to, new StatementSink() {
            @Override public void line(StatementLine line) { lines.add(line); }
        });
        return metadata.withOperations(lines);
    }

    /**
     * Visits the same document projection without retaining its lines in memory.
     * The sink must write to a local spool, not a slow HTTP response: the database
     * transaction ends before a completed export is transferred to the client.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
        propagation = Propagation.REQUIRES_NEW)
    @PreAuthorize(ROLES)
    public Statement scan(UUID accountId, String from, String to, StatementSink sink) {
        return read(accountId, from, to, Objects.requireNonNull(sink));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
        propagation = Propagation.REQUIRES_NEW)
    @PreAuthorize(ROLES)
    public StatementAccount account(UUID accountId) {
        return metadata(access.require(accountId));
    }

    private Statement read(UUID accountId, String fromText, String toText, StatementSink sink) {
        // Resolve access before revealing dates, owner or financial metadata.
        PersonalAccount account = access.require(accountId);
        LocalDate from = date(fromText), to = date(toText);
        if (from.isAfter(to)) throw invalid("Начало периода не должно быть позже его окончания.");
        if (ChronoUnit.DAYS.between(from, to) >= 366)
            throw invalid("Период выписки не должен превышать 366 дней включительно.");
        if (from.isBefore(account.getHistoryStartOn()))
            throw invalid("Начало периода не может быть раньше начала доступной истории счета.");

        StatementAccount owner = metadata(account);
        BigDecimal before = jdbc.queryForObject("""
            select coalesce(sum(delta),0) from financial_operation
            where account_id=? and storage_status='POSTED'
              and operation_date>=? and operation_date<?
            """, BigDecimal.class, accountId, account.getHistoryStartOn(), from);
        BigDecimal opening = Statement.money(account.getOpeningBalance().add(before));
        Totals totals = jdbc.queryForObject("""
            select coalesce(sum(case when delta>0 then delta else 0 end),0) as increase,
                   coalesce(sum(case when delta<0 then -delta else 0 end),0) as decrease
            from financial_operation
            where account_id=? and storage_status='POSTED'
              and operation_date>=? and operation_date<=?
            """, (rs, row) -> new Totals(Statement.money(rs.getBigDecimal("increase")),
                Statement.money(rs.getBigDecimal("decrease"))), accountId, from, to);
        BigDecimal closing = opening.add(totals.increase()).subtract(totals.decrease());
        Statement document = new Statement("1", accountId, owner.accountNumber(), owner.ownerId(),
            owner.ownerName(), owner.status(), owner.historyStartOn(), from, to, Instant.now(),
            owner.currency(), opening, totals.increase(), totals.decrease(), closing, List.of());

        sink.start(document);
        BigDecimal[] running = {opening};
        BigDecimal[] actualIncrease = {ZERO};
        BigDecimal[] actualDecrease = {ZERO};
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(ROWS,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                statement.setFetchSize(256);
                statement.setDate(1, Date.valueOf(to));
                statement.setObject(2, accountId);
                statement.setDate(3, Date.valueOf(from));
                statement.setDate(4, Date.valueOf(to));
                return statement;
            } catch (SQLException | RuntimeException | Error failure) {
                // JdbcTemplate owns the statement only after this creator returns it.
                try { statement.close(); }
                catch (SQLException | RuntimeException | Error closeFailure) {
                    if (closeFailure != failure) failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }, (ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                BigDecimal delta = rs.getBigDecimal("delta");
                running[0] = running[0].add(delta);
                if (delta.signum() > 0) actualIncrease[0] = actualIncrease[0].add(delta);
                else actualDecrease[0] = actualDecrease[0].subtract(delta);
                sink.line(line(rs, running[0]));
            }
            return null;
        });
        if (running[0].compareTo(document.closingBalance()) != 0
            || actualIncrease[0].compareTo(document.increase()) != 0
            || actualDecrease[0].compareTo(document.decrease()) != 0) {
            throw new UserCommandException(HttpStatus.SERVICE_UNAVAILABLE, "STATEMENT_INCONSISTENT",
                "Не удалось согласованно сформировать выписку. Повторите позже.");
        }
        sink.finish(document);
        return document;
    }

    private StatementAccount metadata(PersonalAccount account) {
        String name = jdbc.queryForObject("select display_name from client where id=?",
            String.class, account.getClientId());
        return new StatementAccount(account.getId(), account.getAccountNumber(), account.getClientId(),
            name, account.getHistoryStartOn(), account.getStatus(), account.getCurrency());
    }

    private static StatementLine line(ResultSet rs, BigDecimal balance) throws SQLException {
        Date period = rs.getDate("period_start");
        return new StatementLine(rs.getObject("id", UUID.class), rs.getString("kind"),
            rs.getString("display_status"), rs.getDate("operation_date").toLocalDate(),
            rs.getTimestamp("posted_at").toInstant(), rs.getBigDecimal("amount"),
            rs.getBigDecimal("delta"), rs.getString("external_number"), rs.getString("basis"),
            period == null ? null : period.toLocalDate(), rs.getString("payment_method"),
            rs.getObject("corrects_operation_id", UUID.class), rs.getBigDecimal("target_amount"),
            rs.getString("correction_reason"), balance);
    }

    private static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw invalid("Обе даты обязательны и должны иметь формат ГГГГ-ММ-ДД.");
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw invalid("Указана некорректная календарная дата."); }
    }

    private static UserCommandException invalid(String message) {
        return new UserCommandException(HttpStatus.BAD_REQUEST, "STATEMENT_VALIDATION", message);
    }

    private record Totals(BigDecimal increase, BigDecimal decrease) {}

    public record StatementAccount(UUID id, String accountNumber, UUID ownerId, String ownerName,
        LocalDate historyStartOn, String status, String currency) {}

    public interface StatementSink {
        default void start(Statement metadata) {}
        default void line(StatementLine line) {}
        default void finish(Statement metadata) {}
    }
}
