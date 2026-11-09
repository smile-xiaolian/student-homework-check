package com.edu.submission.audit;

import com.edu.submission.shared.api.TraceIds;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(UUID actorId, String actorType, String action, String targetType,
                       UUID targetId, String result, Map<String, Object> detail,
                       String ip, String traceId) {
        try {
            String json = objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
            jdbcTemplate.update("""
                    INSERT INTO audit.audit_log
                        (id, actor_id, actor_type, action, target_type, target_id, result, detail, ip, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, CAST(? AS inet), ?)
                    """,
                    UUID.randomUUID(), actorId, actorType, action, targetType, targetId,
                    result, json, ip, traceId == null ? TraceIds.current() : traceId);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize audit detail for action {}", action, exception);
        } catch (RuntimeException exception) {
            log.error("Failed to persist audit log for action {}", action, exception);
        }
    }

    public void recordUser(UUID actorId, String action, String targetType, UUID targetId,
                           String result, Map<String, Object> detail, String ip) {
        record(actorId, actorId == null ? "SYSTEM" : "USER", action, targetType, targetId,
                result, detail, ip, TraceIds.current());
    }
}

