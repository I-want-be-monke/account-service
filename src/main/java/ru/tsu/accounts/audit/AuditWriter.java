package ru.tsu.accounts.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(AuditEntry entry) {
        jdbc.update("""
            INSERT INTO audit_event(actor_user_id,actor_type,actor_login_snapshot,category,action,
                                    object_type,object_id,result,reason_code,request_id,before_data,after_data,details)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
            """, entry.actorId(), entry.actorType(),
            safeLogin(entry.actorLogin()), entry.category(), entry.action(), entry.objectType(),
            entry.objectId(), entry.result(), entry.reasonCode(), entry.requestId(),
            json(entry.before()), json(entry.after()), json(entry.details()));
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid audit fields", e); }
    }

    private String safeLogin(String login) {
        if (login == null) return null;
        String safe = login.replaceAll("[\\p{Cntrl}]", "").strip();
        return safe.substring(0, Math.min(100, safe.length()));
    }
}
