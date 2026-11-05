package ru.tsu.accounts.migration;

import static ru.tsu.accounts.migration.MigrationPackageReader.ACCOUNTS;
import static ru.tsu.accounts.migration.MigrationPackageReader.CLIENTS;
import static ru.tsu.accounts.migration.MigrationPackageReader.OPERATIONS;
import static ru.tsu.accounts.migration.MigrationPackageReader.issue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.tsu.accounts.audit.AuditAttemptService;
import ru.tsu.accounts.audit.AuditEntry;
import ru.tsu.accounts.audit.AuditService;
import ru.tsu.accounts.migration.MigrationPackage.Operation;
import ru.tsu.accounts.migration.MigrationValidationReport.Issue;

/** An offline import of new data. Web controllers do not call this service. */
@Service
public class MigrationImportService {
    private static final Logger LOG = LoggerFactory.getLogger(MigrationImportService.class);
    private static final long IMPORT_LOCK = 0x4143435F494D5054L;
    private final MigrationPackageReader reader;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final AuditAttemptService attempts;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    MigrationImportService(MigrationPackageReader reader, JdbcTemplate jdbc, ObjectMapper json,
            AuditService audit, AuditAttemptService attempts, PlatformTransactionManager manager) {
        this.reader = reader;
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.attempts = attempts;
        read = new TransactionTemplate(manager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // After waiting for a concurrent import lock, the next SELECT sees its committed run.
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public MigrationValidationReport dryRun(Path directory, String actorLogin) {
        var result = reader.read(directory);
        if (result.data() == null) return MigrationValidationReport.invalid(result.sha256(), result.issues());
        MigrationPackage data = result.data();
        try {
            return read.execute(transaction -> {
                if (actor(actorLogin, false) == null) return missingActor(data.sha256());
                return checkDatabase(data);
            });
        } catch (DataAccessException | TransactionException ex) {
            return invalid(data.sha256(), "database", "Не удалось проверить целевую БД. Проверьте подключение и миграцию V6.");
        }
    }

    public MigrationValidationReport apply(Path directory, String actorLogin) {
        var result = reader.read(directory);
        if (result.data() == null) return MigrationValidationReport.invalid(result.sha256(), result.issues());
        MigrationPackage data = result.data();
        var attempt = new Attempt();
        try {
            MigrationValidationReport report = write.execute(transaction -> {
                jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, IMPORT_LOCK);
                attempt.actor = actor(actorLogin, true);
                if (attempt.actor == null) return missingActor(data.sha256());
                MigrationValidationReport checked = checkDatabase(data);
                if (!"VALID".equals(checked.status())) return checked;
                return persist(data, attempt.actor, attempt.requestId);
            });
            if (!report.valid()) rejected(attempt, "IMPORT_VALIDATION");
            return report;
        } catch (DataAccessException | TransactionException | InconsistentLedger ex) {
            // The apply transaction is already rolled back here.
            rejected(attempt, "IMPORT_APPLY_FAILED");
            return invalid(data.sha256(), "database", "Импорт откатан. Проверьте доступность БД, ограничения и аудит.");
        }
    }

    private MigrationValidationReport checkDatabase(MigrationPackage data) {
        UUID prior = jdbc.query("select id from import_run where source_sha256=?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, data.sha256());
        if (prior != null) return data.report("ALREADY_APPLIED", prior);

        List<Issue> issues = new ArrayList<>();
        List<Long> clients = jdbc.queryForList("""
            select i.row_no
            from jsonb_to_recordset(cast(? as jsonb)) i(row_no bigint,id uuid,type text,kind text,key text)
            where exists (select 1 from client c where c.id=i.id or
                (c.client_type=i.type and c.identifier_type=i.kind and c.identifier_key=i.key))
            order by i.row_no limit 100
            """, Long.class, json(data.clients().stream().map(c -> Map.of("row_no", c.row(), "id", c.id(),
                "type", c.type(), "kind", c.identifierType(), "key", c.key())).toList()));
        clients.forEach(row -> issue(issues, CLIENTS, row, "UUID или нормализованный идентификатор клиента уже существует."));

        List<Long> accounts = jdbc.queryForList("""
            select i.row_no
            from jsonb_to_recordset(cast(? as jsonb)) i(row_no bigint,id uuid,number text)
            where exists (select 1 from personal_account a where a.id=i.id or a.account_number=i.number)
            order by i.row_no limit 100
            """, Long.class, json(data.accounts().stream().map(a -> Map.of("row_no", a.row(), "id", a.id(),
                "number", a.number())).toList()));
        accounts.forEach(row -> issue(issues, ACCOUNTS, row, "UUID или номер счёта уже существует."));

        // PostgreSQL normalization is also checked so its collation cannot surprise the dry-run.
        List<Long> operations = jdbc.queryForList("""
            with incoming as (
                select i.*, lower(nullif(i.number,'')) as document_key,
                    count(*) over(partition by i.kind,lower(nullif(i.number,''))) as repeated
                from jsonb_to_recordset(cast(? as jsonb)) i(row_no bigint,id uuid,kind text,number text)
            )
            select i.row_no from incoming i
            where (i.document_key is not null and i.repeated>1)
                or exists(select 1 from financial_operation o where o.id=i.id or
                    (o.storage_status='POSTED' and o.external_source='IMPORT'
                     and o.kind=i.kind and o.external_number_key=i.document_key))
            order by i.row_no limit 100
            """, Long.class, json(data.operations().stream().map(o -> Map.of("row_no", o.row(), "id", o.id(),
                "kind", o.kind(), "number", o.number() == null ? "" : o.number())).toList()));
        operations.forEach(row -> issue(issues, OPERATIONS, row, "UUID операции или номер документа IMPORT повторяется."));
        return issues.isEmpty() ? data.report("VALID", null) : MigrationValidationReport.invalid(data.sha256(), issues);
    }

    private Actor actor(String rawLogin, boolean lock) {
        if (rawLogin == null || rawLogin.isBlank()) return null;
        String login = rawLogin.strip().toLowerCase(Locale.ROOT);
        if (login.length() > 100 || login.chars().anyMatch(Character::isISOControl)) return null;
        return jdbc.query("select id,login from app_user where login=? and role_code='ACCOUNTANT' and status='ACTIVE'"
            + (lock ? " for share" : ""), rs -> rs.next()
                ? new Actor(rs.getObject("id", UUID.class), rs.getString("login")) : null, login);
    }

    private MigrationValidationReport persist(MigrationPackage data, Actor actor, UUID requestId) {
        UUID runId = UUID.randomUUID();
        for (var client : data.clients()) {
            jdbc.update("""
                insert into client(id,client_type,display_name,identifier_type,identifier_value,identifier_key,
                    phone,email,status,import_run_id) values(?,?,?,?,?,?,?,?,?,?)
                """, client.id(), client.type(), client.name(), client.identifierType(), client.identifier(),
                client.key(), client.phone(), client.email(), client.status(), runId);
        }
        for (var account : data.accounts()) {
            jdbc.update("""
                insert into personal_account(id,account_number,client_id,status,opened_on,closed_on,history_start_on,
                    opening_balance,current_balance,currency,import_run_id) values(?,?,?,?,?,?,?,?,?,'RUB',?)
                """, account.id(), account.number(), account.clientId(), account.status(), account.openedOn(),
                account.closedOn(), account.historyStartOn(), account.openingBalance(),
                new BigDecimal(data.controlBalances().get(account.number())), runId);
            jdbc.update("""
                insert into account_status_history(account_id,old_status,new_status,changed_by,reason,request_id)
                values(?,null,'OPEN',?,'IMPORT: создание записи счёта',?)
                """, account.id(), actor.id(), requestId);
            if (!account.status().equals("OPEN")) {
                jdbc.update("""
                    insert into account_status_history(account_id,old_status,new_status,changed_by,reason,request_id)
                    values(?,'OPEN',?,?,'IMPORT: состояние из пакета',?)
                    """, account.id(), account.status(), actor.id(), requestId);
            }
        }

        Instant start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int ordinal = 0;
        for (Operation operation : data.operations()) {
            post(operation, actor.id(), runId, data.sha256(), start.plus(++ordinal, ChronoUnit.MICROS));
        }
        reconcile(data, runId);

        var controls = Map.of("counts", data.counts(), "controlBalances", data.controlBalances(),
            "debt", data.debt(), "advance", data.advance());
        jdbc.update("""
            insert into import_run(id,source_name,source_sha256,format_version,initiated_by,control_totals)
            values(?,?,?,'1',?,cast(? as jsonb))
            """, runId, data.sourceName(), data.sha256(), actor.id(), json(controls));
        audit.recordCriticalSuccess(new AuditEntry(actor.id(), actor.login(), "USER", "OPERATIONS",
            "IMPORT_APPLIED", "IMPORT_RUN", runId.toString(), "SUCCESS", null, requestId, null, null,
            Map.of("sourceName", data.sourceName(), "sourceSha256", data.sha256(), "formatVersion", "1",
                "counts", data.counts(), "debt", data.debt(), "advance", data.advance())));
        return data.report("APPLIED", runId);
    }

    private void post(Operation o, UUID actor, UUID runId, String packageSha, Instant instant) {
        jdbc.update("""
            insert into financial_operation(id,account_id,kind,storage_status,operation_date,amount,effect_sign,
                period_start,basis,payment_method,external_source,external_number,external_number_key,
                corrects_operation_id,target_amount,correction_reason,expected_correction_count,created_by,import_run_id)
            values(?,?,?,'PREPARED',?,?,?,?,?,?,?,?,lower(?),?,?,?,?,?,?)
            """, o.id(), o.accountId(), o.kind(), o.date(), o.amount(), o.delta().signum(), o.period(), o.basis(),
            o.method(), o.number() == null ? null : "IMPORT", o.number(), o.number(), o.originalId(),
            o.target(), o.reason(), o.correctionCount(), actor, runId);
        OffsetDateTime posted = instant.atOffset(ZoneOffset.UTC);
        String fingerprint = MigrationPackageReader.sha256(("IMPORT\n" + packageSha + "\n" + o.id())
            .getBytes(StandardCharsets.UTF_8));
        int updated = jdbc.update("""
            update financial_operation set storage_status='POSTED',posted_by=?,posted_at=?,updated_at=?,
                idempotency_key=?,request_fingerprint=?,version=version+1 where id=? and storage_status='PREPARED'
            """, actor, posted, posted, UUID.randomUUID(), fingerprint, o.id());
        if (updated != 1) throw new InconsistentLedger();
    }

    private void reconcile(MigrationPackage data, UUID runId) {
        List<Balance> balances = jdbc.query("""
            select a.account_number,a.current_balance,a.opening_balance+coalesce(sum(o.delta),0) as ledger
            from personal_account a left join financial_operation o on o.account_id=a.id and o.storage_status='POSTED'
            where a.import_run_id=? group by a.id
            """, (rs, index) -> new Balance(rs.getString("account_number"), rs.getBigDecimal("current_balance"),
                rs.getBigDecimal("ledger")), runId);
        if (balances.size() != data.accounts().size()) throw new InconsistentLedger();
        for (Balance balance : balances) {
            String expected = data.controlBalances().get(balance.number());
            if (expected == null || balance.ledger().compareTo(new BigDecimal(expected)) != 0
                || balance.cached().compareTo(balance.ledger()) != 0) throw new InconsistentLedger();
        }
        Long clientCount = jdbc.queryForObject("select count(*) from client where import_run_id=?", Long.class, runId);
        Long postedCount = jdbc.queryForObject("select count(*) from financial_operation where import_run_id=? and storage_status='POSTED'",
            Long.class, runId);
        if (clientCount != data.clients().size() || postedCount != data.operations().size())
            throw new InconsistentLedger();
    }

    private void rejected(Attempt attempt, String code) {
        if (attempt.actor == null) return;
        try {
            attempts.record(AuditEntry.denied(attempt.actor.id(), attempt.actor.login(), "OPERATIONS",
                "IMPORT_REJECTED", "IMPORT_RUN", null, code, attempt.requestId));
        } catch (DataAccessException | TransactionException ex) {
            LOG.error("Import rejection audit unavailable; requestId={}", attempt.requestId);
        }
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Import metadata could not be encoded", ex); }
    }

    private static MigrationValidationReport missingActor(String sha) {
        return invalid(sha, "actor", "Укажите действующего исполнителя с ролью ACCOUNTANT.");
    }

    private static MigrationValidationReport invalid(String sha, String source, String message) {
        return MigrationValidationReport.invalid(sha, List.of(new Issue(source, 0, message)));
    }

    private record Actor(UUID id, String login) {}
    private record Balance(String number, BigDecimal cached, BigDecimal ledger) {}
    private static class InconsistentLedger extends RuntimeException {}
    private static class Attempt {
        final UUID requestId = UUID.randomUUID();
        Actor actor;
    }
}
