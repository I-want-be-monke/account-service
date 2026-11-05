package ru.tsu.accounts.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.tsu.accounts.identity.UserCommandException;

@Service
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class AuditQueryService {
    private static final Set<String> CATEGORIES = Set.of("SECURITY", "BUSINESS", "ACCESS", "OPERATIONS");
    private static final Set<String> RESULTS = Set.of("SUCCESS", "DENIED", "FAILED", "REPLAYED");
    private final NamedParameterJdbcTemplate jdbc;

    public AuditQueryService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public AuditPage search(AuditSearch search) {
        boolean admin = isAdmin();
        LocalDate from = search.from() == null ? LocalDate.now(ZoneOffset.UTC).minusDays(30) : search.from();
        LocalDate to = search.to() == null ? LocalDate.now(ZoneOffset.UTC) : search.to();
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 366
            || search.page() < 0 || search.page() > 100000)
            throw badFilter();
        if (present(search.category()) && !CATEGORIES.contains(search.category())
            || present(search.result()) && !RESULTS.contains(search.result())) throw badFilter();
        if (!admin && "SECURITY".equals(search.category()))
            throw new UserCommandException(HttpStatus.FORBIDDEN, "AUDIT_FORBIDDEN", "Этот раздел журнала недоступен.");

        StringBuilder where = new StringBuilder(" WHERE occurred_at >= :from AND occurred_at < :until");
        Map<String,Object> values = new HashMap<>();
        values.put("from", Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)));
        values.put("until", Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        if (!admin) where.append(" AND category IN ('BUSINESS','ACCESS')");
        add(where, values, "actor_user_id", "actor", search.actorId());
        add(where, values, "object_type", "objectType", search.objectType());
        add(where, values, "object_id", "objectId", search.objectId());
        add(where, values, "action", "action", search.action());
        add(where, values, "result", "result", search.result());
        add(where, values, "category", "category", search.category());
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_event" + where, values, Long.class);
        values.put("limit", 20);
        values.put("offset", search.page() * 20);
        var rows = jdbc.query("""
            SELECT id,occurred_at,actor_user_id,actor_login_snapshot,category,action,object_type,
                   object_id,result,reason_code,request_id,before_data::text AS before_text,
                   after_data::text AS after_text,details::text AS details_text FROM audit_event
            """ + where + " ORDER BY occurred_at DESC,id DESC LIMIT :limit OFFSET :offset", values,
            this::map);
        return new AuditPage(rows, count == null ? 0 : count, search.page(), 20);
    }

    @Transactional(readOnly = true)
    public AuditRow get(long id) {
        boolean admin = isAdmin();
        var rows = jdbc.query("""
            SELECT id,occurred_at,actor_user_id,actor_login_snapshot,category,action,object_type,
                   object_id,result,reason_code,request_id,before_data::text AS before_text,
                   after_data::text AS after_text,details::text AS details_text FROM audit_event WHERE id=:id
            """, Map.of("id", id), this::map);
        if (rows.isEmpty()) throw new UserCommandException(HttpStatus.NOT_FOUND, "AUDIT_NOT_FOUND", "Событие не найдено.");
        AuditRow row = rows.getFirst();
        if (!admin && !Set.of("BUSINESS", "ACCESS").contains(row.category()))
            throw new UserCommandException(HttpStatus.FORBIDDEN, "AUDIT_FORBIDDEN", "Это событие недоступно.");
        return row;
    }

    private AuditRow map(ResultSet rs, int index) throws SQLException {
        return new AuditRow(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("actor_user_id", UUID.class), rs.getString("actor_login_snapshot"),
            rs.getString("category"), rs.getString("action"), rs.getString("object_type"),
            rs.getString("object_id"), rs.getString("result"), rs.getString("reason_code"),
            rs.getObject("request_id", UUID.class), rs.getString("before_text"), rs.getString("after_text"),
            rs.getString("details_text"));
    }

    private void add(StringBuilder where, Map<String,Object> values, String column, String key, Object value) {
        if (value == null || value instanceof String s && s.isBlank()) return;
        if (value instanceof String s && s.length() > 64) throw badFilter();
        where.append(" AND ").append(column).append(" = :").append(key);
        values.put(key, value);
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
    private boolean isAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
    private UserCommandException badFilter() {
        return new UserCommandException(HttpStatus.BAD_REQUEST, "AUDIT_FILTER_INVALID", "Некорректные фильтры журнала.");
    }
}
