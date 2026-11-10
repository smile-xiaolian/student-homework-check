package com.edu.submission.checking.application;

import com.edu.submission.checking.adapter.in.dto.CheckReportSummary;
import com.edu.submission.checking.adapter.in.dto.CheckReportView;
import com.edu.submission.checking.adapter.in.dto.CheckTaskView;
import com.edu.submission.course.application.CourseService;
import com.edu.submission.course.domain.Course;
import com.edu.submission.shared.api.ApiException;
import com.edu.submission.shared.crypto.Digests;
import com.edu.submission.shared.ids.Ids;
import com.edu.submission.shared.security.UserPrincipal;
import com.edu.submission.shared.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

@Service
public class CheckingQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CourseService courseService;
    private final ObjectStorageService storageService;

    public CheckingQueryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                CourseService courseService, ObjectStorageService storageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.courseService = courseService;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public CheckTaskView task(UserPrincipal principal, UUID taskId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, subject_type, task_type, assignment_id, submission_id, requester_id, " +
                        "status, attempt, max_attempts, scheduled_at, started_at, finished_at, error_code, error_detail " +
                        "FROM app.check_task WHERE id = ?", taskId);
        UUID submissionId = (UUID) row.get("submission_id");
        if (submissionId != null) {
            requireSubmissionReportAccess(principal, submissionRef(submissionId));
        } else {
            UUID requesterId = (UUID) row.get("requester_id");
            if (!principal.id().equals(requesterId) && !"ADMIN".equals(principal.role().name())) {
                UUID assignmentId = (UUID) row.get("assignment_id");
                requireAssignmentReadable(principal, assignmentId);
            }
        }
        String status = (String) row.get("status");
        List<Map<String, Object>> reports = jdbcTemplate.queryForList(
                "SELECT id, report_version FROM app.check_report WHERE check_task_id = ? ORDER BY report_version DESC LIMIT 1",
                taskId);
        UUID reportId = reports.isEmpty() ? null : (UUID) reports.get(0).get("id");
        Integer reportVersion = reports.isEmpty()
                ? null : ((Number) reports.get(0).get("report_version")).intValue();
        Map<String, Object> error = null;
        if (row.get("error_code") != null) {
            int attempt = ((Number) row.get("attempt")).intValue();
            int maxAttempts = ((Number) row.get("max_attempts")).intValue();
            error = Map.of(
                    "code", row.get("error_code"),
                    "detail", row.get("error_detail") == null ? "查重任务执行失败。" : row.get("error_detail"),
                    "category", attempt >= maxAttempts ? "PERMANENT" : "RETRYABLE");
        }
        return new CheckTaskView(
                taskId, (String) row.get("subject_type"), (String) row.get("task_type"),
                (UUID) row.get("assignment_id"), submissionId, status, stage(status), progress(status),
                reportId, reportVersion, ((Number) row.get("attempt")).intValue(),
                ((Number) row.get("max_attempts")).intValue(), active(status) ? 3 : 0, error,
                toOffset(row.get("scheduled_at")), toOffset(row.get("started_at")), toOffset(row.get("finished_at")));
    }

    @Transactional(readOnly = true)
    public CheckReportView currentReport(UserPrincipal principal, UUID submissionId) {
        SubmissionRef submission = requireSubmissionReportAccess(principal, submissionRef(submissionId));
        List<Map<String, Object>> rows = reportRows("r.submission_id = ? AND r.is_current = true", submissionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("CHECK_REPORT_NOT_FOUND", "查重报告尚未生成",
                    "任务可能仍在排队或执行中，请稍后再试。");
        }
        return toReportView(rows.get(0));
    }

    @Transactional(readOnly = true)
    public List<CheckReportSummary> reportHistory(UserPrincipal principal, UUID submissionId) {
        requireSubmissionReportAccess(principal, submissionRef(submissionId));
        return jdbcTemplate.query(
                "SELECT id, report_version, is_current, overall_score, level, generated_at " +
                        "FROM app.check_report WHERE submission_id = ? ORDER BY report_version DESC",
                (rs, index) -> new CheckReportSummary(
                        rs.getObject("id", UUID.class), rs.getInt("report_version"),
                        rs.getBoolean("is_current"), rs.getBigDecimal("overall_score"),
                        rs.getString("level"), rs.getObject("generated_at", OffsetDateTime.class)),
                submissionId);
    }

    @Transactional(readOnly = true)
    public CheckReportView report(UserPrincipal principal, UUID reportId) {
        Map<String, Object> row = requireReportRow(reportId);
        authorizeReport(principal, row);
        return toReportView(row);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> segments(UserPrincipal principal, UUID reportId, Integer size) {
        return contentPage(principal, reportId, "segments", size);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> sources(UserPrincipal principal, UUID reportId, Integer size) {
        return contentPage(principal, reportId, "sources", size);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> manifest(UserPrincipal principal, UUID reportId, String bucket,
                                        Boolean included) {
        Map<String, Object> row = requireReportRow(reportId);
        authorizeReport(principal, row);
        StringBuilder sql = new StringBuilder(
                "SELECT path, file_name, extension, size_bytes, sha256, bucket, included, exclude_reason, " +
                        "language, is_binary FROM app.file_classification WHERE report_id = ?");
        List<Object> arguments = new ArrayList<>(List.of(reportId));
        if (bucket != null && !bucket.isBlank()) {
            sql.append(" AND bucket = ?");
            arguments.add(bucket);
        }
        if (included != null) {
            sql.append(" AND included = ?");
            arguments.add(included);
        }
        sql.append(" ORDER BY sort_order, path");
        List<Map<String, Object>> items = jdbcTemplate.queryForList(sql.toString(), arguments.toArray());
        return Map.of("overview", Map.of("totalFiles", items.size(),
                "includedFiles", items.stream().filter(item -> Boolean.TRUE.equals(item.get("included"))).count()),
                "items", items, "nextCursor", "");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> verify(UserPrincipal principal, UUID reportId) {
        Map<String, Object> row = requireReportRow(reportId);
        authorizeReport(principal, row);
        String expected = (String) row.get("report_digest");
        byte[] bytes = loadReportBytes(row);
        boolean valid = Digests.sha256Hex(bytes).equalsIgnoreCase(expected);
        return Map.of("reportId", reportId, "reportDigest", expected, "signatureValid", valid,
                "algorithmVersion", row.get("algorithm_version"), "verifiedAt", OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public String html(UserPrincipal principal, UUID reportId) {
        Map<String, Object> row = requireReportRow(reportId);
        authorizeReport(principal, row);
        JsonNode document = reportDocument(row);
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <style>body{font-family:Segoe UI,PingFang SC,sans-serif;max-width:960px;margin:40px auto;padding:0 24px;color:#263238}mark{background:#ffe6a3}.meta{color:#667;font-size:13px}.segment{border:1px solid #e5e9ec;border-radius:8px;padding:14px;margin:12px 0}.source{padding:10px 0;border-bottom:1px solid #eee}.score{font-size:42px;font-weight:800;color:#0f766e}</style>
                </head><body>
                """);
        html.append("<h1>原创性检测报告</h1><p class=\"score\">")
                .append(HtmlUtils.htmlEscape(String.valueOf(row.get("overall_score"))))
                .append("%</p><p class=\"meta\">版本 V")
                .append(HtmlUtils.htmlEscape(String.valueOf(row.get("report_version"))))
                .append(" · 算法 ")
                .append(HtmlUtils.htmlEscape(String.valueOf(row.get("algorithm_version"))))
                .append(" · 生成于 ")
                .append(HtmlUtils.htmlEscape(String.valueOf(row.get("generated_at"))))
                .append("</p><h2>分段比对</h2>");
        JsonNode segments = document.path("segments");
        if (segments.isArray()) {
            for (JsonNode segment : segments) {
                html.append("<div class=\"segment\"><p>")
                        .append(HtmlUtils.htmlEscape(segment.path("originalText").asText("")))
                        .append("</p><small>相似度 ")
                        .append(HtmlUtils.htmlEscape(segment.path("similarity").asText("0")))
                        .append("%</small></div>");
            }
        }
        html.append("<h2>相似来源</h2>");
        JsonNode sources = document.path("sources");
        if (sources.isArray()) {
            for (JsonNode source : sources) {
                html.append("<div class=\"source\"><strong>")
                        .append(HtmlUtils.htmlEscape(source.path("title").asText("相似来源")))
                        .append("</strong> · ")
                        .append(HtmlUtils.htmlEscape(source.path("similarity").asText("0")))
                        .append("%<p>")
                        .append(HtmlUtils.htmlEscape(source.path("excerpt").asText("")))
                        .append("</p></div>");
            }
        }
        return html.append("</body></html>").toString();
    }

    @Transactional
    public Map<String, Object> recheck(UserPrincipal principal, UUID submissionId, String reason,
                                       String idempotencyKey) {
        SubmissionRef submission = submissionRef(submissionId);
        requireSubmissionManage(principal, submission);
        Map<String, Object> assignment = jdbcTemplate.queryForMap(
                "SELECT check_config::text AS config FROM app.assignment WHERE id = ?", submission.assignmentId());
        String config = (String) assignment.get("config");
        if (config == null || config.isBlank()) {
            config = "{}";
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "RECHECK:" + submissionId + ":" + System.currentTimeMillis()
                : "RECHECK:" + idempotencyKey;
        UUID taskId = Ids.newUuidV7();
        jdbcTemplate.update(
                "INSERT INTO app.check_task (id, subject_type, submission_id, assignment_id, requester_id, " +
                        "task_type, status, task_version, attempt, max_attempts, idempotency_key, config_snapshot, " +
                        "config_digest, library_watermarks, scheduled_at, created_by) " +
                        "VALUES (?, 'SUBMISSION', ?, ?, ?, 'RECHECK', 'QUEUED', 0, 0, 3, ?, ?::jsonb, ?, '{}'::jsonb, now(), ?)",
                taskId, submissionId, submission.assignmentId(), principal.id(), key, config,
                Digests.sha256Hex(config), principal.id());
        jdbcTemplate.update("UPDATE app.submission SET status = 'CHECKING', row_version = row_version + 1 WHERE id = ?",
                submissionId);
        return Map.of("taskId", taskId, "submissionId", submissionId, "status", "QUEUED",
                "reason", reason == null ? "" : reason);
    }

    private Map<String, Object> contentPage(UserPrincipal principal, UUID reportId, String field, Integer size) {
        Map<String, Object> row = requireReportRow(reportId);
        authorizeReport(principal, row);
        JsonNode document = reportDocument(row);
        JsonNode node = document.path(field);
        List<JsonNode> items = new ArrayList<>();
        if (node.isArray()) {
            int limit = Math.min(size == null ? 50 : Math.max(1, size), 200);
            node.elements().forEachRemaining(item -> {
                if (items.size() < limit) {
                    items.add(item);
                }
            });
        }
        return Map.of("items", items, "nextCursor", "");
    }

    private JsonNode reportDocument(Map<String, Object> row) {
        try {
            return objectMapper.readTree(loadReportBytes(row));
        } catch (Exception exception) {
            throw ApiException.unprocessable("CHECK_REPORT_INVALID", "报告内容无法解析",
                    "报告文件可能损坏，请申请重新查重。");
        }
    }

    private byte[] loadReportBytes(Map<String, Object> row) {
        String objectKey = (String) row.get("report_object_key");
        if (objectKey == null) {
            throw ApiException.notFound("CHECK_REPORT_FILE_NOT_FOUND", "报告文件不存在",
                    "报告对象可能尚未写入对象存储。");
        }
        return storageService.get(objectKey);
    }

    private Map<String, Object> requireReportRow(UUID reportId) {
        List<Map<String, Object>> rows = reportRows("r.id = ?", reportId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("CHECK_REPORT_NOT_FOUND", "查重报告不存在", "报告可能已被删除。");
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> reportRows(String where, Object argument) {
        return jdbcTemplate.queryForList(
                "SELECT r.*, r.params::text AS params_text, f.object_key AS report_object_key, " +
                        "f.bucket AS report_bucket " +
                        "FROM app.check_report r JOIN app.file_object f ON f.id = r.report_file_id WHERE " + where +
                        " ORDER BY r.report_version DESC", argument);
    }

    private void authorizeReport(UserPrincipal principal, Map<String, Object> row) {
        UUID submissionId = (UUID) row.get("submission_id");
        if (submissionId != null) {
            requireSubmissionReportAccess(principal, submissionRef(submissionId));
            return;
        }
        UUID requesterId = (UUID) row.get("requester_id");
        if (!principal.id().equals(requesterId) && !"ADMIN".equals(principal.role().name())) {
            throw ApiException.forbidden("CHECK_REPORT_FORBIDDEN", "无权查看报告", "该报告不属于当前账号。");
        }
    }

    private CheckReportView toReportView(Map<String, Object> row) {
        UUID reportId = (UUID) row.get("id");
        Map<String, String> links = new LinkedHashMap<>();
        links.put("segments", "/api/v1/check-reports/" + reportId + "/segments");
        links.put("sources", "/api/v1/check-reports/" + reportId + "/sources");
        links.put("manifest", "/api/v1/check-reports/" + reportId + "/manifest");
        links.put("html", "/api/v1/check-reports/" + reportId + "/html");
        links.put("verify", "/api/v1/check-reports/" + reportId + "/verify");
        JsonNode params;
        try {
            params = objectMapper.readTree((String) row.get("params_text"));
        } catch (Exception exception) {
            params = objectMapper.createObjectNode();
        }
        return new CheckReportView(
                reportId, (UUID) row.get("check_task_id"), (UUID) row.get("submission_id"),
                (UUID) row.get("assignment_id"), ((Number) row.get("report_version")).intValue(),
                Boolean.TRUE.equals(row.get("is_current")), decimal(row.get("overall_score")),
                decimal(row.get("text_score")), decimal(row.get("structure_score")),
                (String) row.get("level"), (String) row.get("algorithm_version"), params,
                ((Number) row.get("source_count")).intValue(), (String) row.get("top_source_type"),
                decimal(row.get("top_source_similarity")), (String) row.get("report_digest"),
                toOffset(row.get("generated_at")), links);
    }

    private SubmissionRef submissionRef(UUID submissionId) {
        List<SubmissionRef> rows = jdbcTemplate.query(
                "SELECT s.id, s.assignment_id, s.submitter_id, a.course_id FROM app.submission s " +
                        "JOIN app.assignment a ON a.id = s.assignment_id WHERE s.id = ?",
                (rs, index) -> new SubmissionRef(rs.getObject("id", UUID.class),
                        rs.getObject("assignment_id", UUID.class), rs.getObject("submitter_id", UUID.class),
                        rs.getObject("course_id", UUID.class)), submissionId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("SUBMISSION_NOT_FOUND", "提交不存在", "提交可能已被删除。");
        }
        return rows.get(0);
    }

    private SubmissionRef requireSubmissionReadable(UserPrincipal principal, SubmissionRef submission) {
        Course course = courseService.requireCourse(submission.courseId());
        courseService.requireReadable(course, principal);
        return submission;
    }

    private SubmissionRef requireSubmissionReportAccess(UserPrincipal principal, SubmissionRef submission) {
        if (submission.submitterId().equals(principal.id())) {
            return requireSubmissionReadable(principal, submission);
        }
        requireSubmissionManage(principal, submission);
        return submission;
    }

    private void requireSubmissionManage(UserPrincipal principal, SubmissionRef submission) {
        Course course = courseService.requireCourse(submission.courseId());
        courseService.requireManage(course, principal);
    }

    private void requireAssignmentReadable(UserPrincipal principal, UUID assignmentId) {
        List<UUID> courseIds = jdbcTemplate.query(
                "SELECT course_id FROM app.assignment WHERE id = ?",
                (rs, index) -> rs.getObject("course_id", UUID.class), assignmentId);
        if (courseIds.isEmpty()) {
            throw ApiException.notFound("ASSIGNMENT_NOT_FOUND", "作业不存在", "作业可能已被删除。");
        }
        Course course = courseService.requireCourse(courseIds.get(0));
        courseService.requireReadable(course, principal);
    }

    private boolean active(String status) {
        return List.of("QUEUED", "PREPROCESSING", "COMPARING", "REPORTING").contains(status);
    }

    private int progress(String status) {
        return switch (status) {
            case "QUEUED" -> 5;
            case "PREPROCESSING" -> 20;
            case "COMPARING" -> 65;
            case "REPORTING" -> 90;
            case "SUCCEEDED" -> 100;
            default -> 0;
        };
    }

    private String stage(String status) {
        return switch (status) {
            case "QUEUED" -> "排队等待中";
            case "PREPROCESSING" -> "正在预处理文本";
            case "COMPARING" -> "正在比对比对库";
            case "REPORTING" -> "正在生成报告";
            case "SUCCEEDED" -> "查重完成";
            case "FAILED" -> "查重失败";
            default -> "任务已取消";
        };
    }

    private BigDecimal decimal(Object value) {
        return value == null ? null : (BigDecimal) value;
    }

    private OffsetDateTime toOffset(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        // JdbcTemplate 查出的 timestamptz 列是 java.sql.Timestamp，不是 OffsetDateTime
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        throw new IllegalArgumentException("Unsupported timestamp type: " + value.getClass().getName());
    }

    private record SubmissionRef(UUID submissionId, UUID assignmentId, UUID submitterId, UUID courseId) {
    }
}
