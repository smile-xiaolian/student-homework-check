package com.edu.submission.admin.application;

import com.edu.submission.admin.adapter.in.dto.SystemConfigUpdateRequest;
import com.edu.submission.admin.adapter.in.dto.SystemConfigView;
import com.edu.submission.audit.AuditService;
import com.edu.submission.checking.application.CheckTaskService;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.api.PageQuery;
import com.edu.submission.shared.api.PageResult;
import com.edu.submission.shared.api.Pagination;
import com.edu.submission.shared.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminSystemService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CheckTaskService checkTaskService;
    private final AuditService auditService;

    public AdminSystemService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              CheckTaskService checkTaskService, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.checkTaskService = checkTaskService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SystemConfigView> config() {
        return jdbcTemplate.query(
                "SELECT config_key, value::text AS value, value_type, config_group, description, version, updated_at " +
                        "FROM app.system_config ORDER BY config_group, config_key",
                (rs, index) -> new SystemConfigView(
                        rs.getString("config_key"), readJson(rs.getString("value")),
                        rs.getString("value_type"), rs.getString("config_group"),
                        rs.getString("description"), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class)));
    }

    @Transactional
    public List<SystemConfigView> updateConfig(UserPrincipal principal, SystemConfigUpdateRequest request) {
        for (SystemConfigUpdateRequest.Item item : request.items()) {
            int updated = jdbcTemplate.update(
                    "UPDATE app.system_config SET value = ?::jsonb, version = version + 1, " +
                            "updated_by = ?, updated_at = now() WHERE config_key = ? AND version = ?",
                    writeJson(item.value()), principal.id(), item.key(), item.version());
            if (updated == 0) {
                throw ApiException.conflict("SYSTEM_CONFIG_VERSION_CONFLICT", "系统参数已被更新",
                        "参数 " + item.key() + " 已被其他管理员修改，请刷新后重试。");
            }
        }
        auditService.recordUser(principal.id(), "system.config.update", "SYSTEM_CONFIG", null,
                "SUCCESS", Map.of("keys", request.items().stream().map(SystemConfigUpdateRequest.Item::key).toList()), null);
        return config();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> monitoring() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("generatedAt", OffsetDateTime.now());
        result.put("userCount", count("SELECT count(*) FROM app.user_account"));
        result.put("activeUserCount", count("SELECT count(*) FROM app.user_account WHERE status = 'ACTIVE'"));
        result.put("courseCount", count("SELECT count(*) FROM app.course WHERE status <> 'ARCHIVED'"));
        result.put("assignmentCount", count("SELECT count(*) FROM app.assignment WHERE status = 'PUBLISHED'"));
        result.put("submissionCount", count("SELECT count(*) FROM app.submission"));
        result.put("queuedCheckTasks", checkTaskService.queueLength());
        result.put("runningCheckTasks", checkTaskService.runningCount());
        result.put("failedCheckTasks", count("SELECT count(*) FROM app.check_task WHERE status = 'FAILED'"));
        result.put("reportsToday", count("SELECT count(*) FROM app.check_report WHERE generated_at >= current_date"));
        result.put("unreadNotifications", count("SELECT count(*) FROM app.notification WHERE is_read = false"));
        return result;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> audits(Integer page, Integer size, String action,
                                                   String actorId, String result) {
        PageQuery query = PageQuery.of(page, size);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            where.append(" AND action LIKE ?");
            arguments.add("%" + action.trim() + "%");
        }
        if (actorId != null && !actorId.isBlank()) {
            where.append(" AND actor_id = ?::uuid");
            arguments.add(actorId.trim());
        }
        if (result != null && !result.isBlank()) {
            where.append(" AND result = ?");
            arguments.add(result.trim());
        }
        long total = jdbcTemplate.queryForObject("SELECT count(*) FROM audit.audit_log" + where,
                Long.class, arguments.toArray());
        arguments.add(query.size());
        arguments.add(query.offset());
        List<Map<String, Object>> items = jdbcTemplate.query(
                "SELECT id, actor_id, actor_type, action, target_type, target_id, result, detail::text AS detail, " +
                        "host(ip) AS ip, trace_id, created_at FROM audit.audit_log" + where +
                        " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, index) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id"));
                    row.put("actorId", rs.getObject("actor_id"));
                    row.put("actorType", rs.getString("actor_type"));
                    row.put("action", rs.getString("action"));
                    row.put("targetType", rs.getString("target_type"));
                    row.put("targetId", rs.getObject("target_id"));
                    row.put("result", rs.getString("result"));
                    row.put("detail", readJson(rs.getString("detail")));
                    row.put("ip", rs.getString("ip"));
                    row.put("traceId", rs.getString("trace_id"));
                    row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                    return row;
                }, arguments.toArray());
        return new PageResult<>(items, Pagination.of(query.page(), query.size(), total));
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private JsonNode readJson(String value) {
        try {
            return value == null ? objectMapper.nullNode() : objectMapper.readTree(value);
        } catch (Exception exception) {
            return objectMapper.nullNode();
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw ApiException.unprocessable("SYSTEM_CONFIG_INVALID", "参数格式错误", "请检查 JSON 参数格式。");
        }
    }
}
